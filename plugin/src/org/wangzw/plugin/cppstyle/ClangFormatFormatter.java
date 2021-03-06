package org.wangzw.plugin.cppstyle;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.texteditor.ITextEditor;
import org.wangzw.plugin.cppstyle.diff_match_patch.Diff;
import org.wangzw.plugin.cppstyle.ui.CppStyleConstants;
import org.wangzw.plugin.cppstyle.ui.CppStyleMessageConsole;

public class ClangFormatFormatter {
	private MessageConsoleStream err = null;
	private Map<String, ?> options;

	public ClangFormatFormatter() {
		super();
		CppStyleMessageConsole console = CppStyle.buildConsole();
		err = console.getErrorStream();
	}

	public void formatAndApply(ITextEditor editor) {
		IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());

		String path = ((IFileEditorInput) editor.getEditorInput()).getFile().getLocation().toOSString();
		TextEdit res = format(doc.get(), path, null);

		if (res == null) {
			return;
		}

		IDocumentUndoManager manager = DocumentUndoManagerRegistry.getDocumentUndoManager(doc);
		manager.beginCompoundChange();

		try {
			res.apply(doc);
		} catch (MalformedTreeException e) {
			CppStyle.log("Failed to apply change", e);
		} catch (BadLocationException e) {
			CppStyle.log("Failed to apply change", e);
		}

		manager.endCompoundChange();

	}

	private TextEdit format(String source, String path, IRegion region) {
		String clangFormatPath = getClangFormatPath();
		if (checkClangFormat(clangFormatPath) == false) {
			return null;
		}

		String confPath = getClangFormatConfigureFile(path);
		if (confPath == null) {
			err.println(
					"Cannot find .clang-format or _clang-format configuration file under any level "
							+ "parent directories of path (" + path + ").");
			err.println("Not applying any formatting.");
			return null;
		}

		// make clang-format do its own search for the configuration, but fall back to Google.
		String stdArg = "-style=file";
		String fallbackArg = "-fallback-style=Google";

		ArrayList<String> commands = new ArrayList<String>(
				Arrays.asList(clangFormatPath, "-assume-filename=" + path, stdArg, fallbackArg));

		StringBuffer sb = new StringBuffer();
		sb.append(stdArg + " " + fallbackArg + " ");

		if (region != null) {
			commands.add("-offset=" + region.getOffset());
			commands.add("-length=" + region.getLength());

			sb.append("-offset=");
			sb.append(region.getOffset());
			sb.append(" -length=");
			sb.append(region.getLength());
			sb.append(' ');
		}

		ProcessBuilder builder = new ProcessBuilder(commands);

		String root = ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
		builder.directory(new File(root));

		try {
			Process process = builder.start();
			OutputStreamWriter output = new OutputStreamWriter(process.getOutputStream());

			output.write(source);
			output.flush();
			output.close();

			InputStreamReader reader = new InputStreamReader(process.getInputStream());
			InputStreamReader error = new InputStreamReader(process.getErrorStream());

			final char[] buffer = new char[1024];
			final StringBuilder stdout = new StringBuilder();
			final StringBuilder errout = new StringBuilder();

			for (;;) {
				int rsz = reader.read(buffer, 0, buffer.length);

				if (rsz < 0) {
					break;
				}

				stdout.append(buffer, 0, rsz);
			}

			for (;;) {
				int rsz = error.read(buffer, 0, buffer.length);

				if (rsz < 0) {
					break;
				}

				errout.append(buffer, 0, rsz);
			}

			String newSource = stdout.toString();

			int code = process.waitFor();
			if (code != 0) {
				err.println("clang-format return error (" + code + ").");
				err.println(errout.toString());
				return null;
			}

			if (errout.length() > 0) {
				err.println(errout.toString());
				return null;
			}

			if (0 == source.compareTo(newSource)) {
				return null;
			}

			diff_match_patch diff = new diff_match_patch();

			LinkedList<Diff> diffs = diff.diff_main(source, newSource);
			diff.diff_cleanupEfficiency(diffs);

			int offset = 0;
			MultiTextEdit edit = new MultiTextEdit();

			for (Diff d : diffs) {
				switch (d.operation) {
				case INSERT:
					InsertEdit e = new InsertEdit(offset, d.text);
					edit.addChild(e);
					break;
				case DELETE:
					DeleteEdit e1 = new DeleteEdit(offset, d.text.length());
					offset += d.text.length();
					edit.addChild(e1);
					break;
				case EQUAL:
					offset += d.text.length();
					break;
				}
			}

			return edit;

		} catch (IOException e) {
			CppStyle.log("Failed to format code", e);
		} catch (InterruptedException e) {
			CppStyle.log("Failed to format code", e);
		}

		return null;
	}

	private String getClangFormatConfigureFile(String path) {
		File file = new File(path);

		while (file != null) {
			File dir = file.getParentFile();
			if (dir != null) {
				File conf = new File(dir, ".clang-format");
				if (conf.exists()) {
					return conf.getAbsolutePath();
				}

				conf = new File(dir, "_clang-format");
				if (conf.exists()) {
					return conf.getAbsolutePath();
				}
			}

			file = dir;
		}

		return null;
	}

	public boolean checkClangFormat(String clangformat) {
		if (clangformat == null) {
			err.println("clang-format is not specified.");
			return false;
		}

		File file = new File(clangformat);

		if (!file.exists()) {
			err.println("clang-format (" + clangformat + ") does not exist.");
			return false;
		}

		if (!file.canExecute()) {
			err.println("clang-format (" + clangformat + ") is not executable.");
			return false;
		}

		return true;
	}

	private boolean enableClangFormatOnSave(IResource resource) {
		boolean enable = CppStyle.getDefault().getPreferenceStore()
				.getBoolean(CppStyleConstants.ENABLE_CLANGFORMAT_ON_SAVE);

		try {
			IProject project = resource.getProject();
			String enableProjectSpecific = project
					.getPersistentProperty(new QualifiedName("", CppStyleConstants.PROJECTS_PECIFIC_PROPERTY));

			if (enableProjectSpecific != null && Boolean.parseBoolean(enableProjectSpecific)) {
				String value = project
						.getPersistentProperty(new QualifiedName("", CppStyleConstants.ENABLE_CLANGFORMAT_PROPERTY));
				if (value != null) {
					return Boolean.parseBoolean(value);
				}

				return false;
			}
		} catch (CoreException e) {
			CppStyle.log(e);
		}

		return enable;
	}

	public boolean runClangFormatOnSave(IResource resource) {
		if (!enableClangFormatOnSave(resource)) {
			return false;
		}

		String clangFormat = getClangFormatPath();

		if (clangFormat == null) {
			err.println("clang-format command must be specified in preferences.");
			return false;
		}

		File file = new File(clangFormat);

		if (!file.exists()) {
			err.println("clang-format (" + clangFormat + ") does not exist.");
			return false;
		}

		if (!file.canExecute()) {
			err.println("clang-format (" + clangFormat + ") is not executable.");
			return false;
		}

		return true;
	}

	public static String getClangFormatPath() {
		return CppStyle.getDefault().getPreferenceStore().getString(CppStyleConstants.CLANG_FORMAT_PATH);
	}

}
