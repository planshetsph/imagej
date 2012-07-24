package imagej.updater.gui;

import imagej.log.LogService;
import imagej.updater.core.Diff;
import imagej.updater.core.Diff.Mode;
import imagej.updater.core.FileObject;
import imagej.updater.core.FilesCollection;
import imagej.updater.util.ByteCodeAnalyzer;
import imagej.util.FileUtils;

import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.swing.JFrame;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

/**
 * A {@link JFrame} to show the differences between the remote and local
 * versions of a file known to the ImageJ Updater.
 * 
 * @author Johannes Schindelin
 */
public class DiffFile extends JFrame {
	private static final long serialVersionUID = 1L;
	protected LogService log;
	protected String filename;
	protected URL remote, local;
	protected DiffView diffView;
	protected Cursor normalCursor, waitCursor;
	protected Diff diff;
	protected int diffOffset;
	protected Thread worker;

	/**
	 * Initialize the frame.
	 * 
	 * @param files
	 *            the collection of files, including information about the
	 *            update site from which we got the file
	 * @param file
	 *            the file to diff
	 * @param mode
	 *            the diff mode
	 * @throws MalformedURLException
	 */
	public DiffFile(final FilesCollection files, final FileObject file, final Mode mode) throws MalformedURLException {
		super(file.getLocalFilename() + " differences");

		log = files.log;
		filename = file.getLocalFilename();
		remote = new URL(files.getURL(file));
		local = files.prefix(file.getLocalFilename()).toURI().toURL();

		diffView = new DiffView();
		normalCursor = diffView.getCursor();
		waitCursor = new Cursor(Cursor.WAIT_CURSOR);
		addModeLinks();
		addGitLogLink(files, file);
		diffOffset = diffView.getDocument().getLength();
		diff = new Diff(diffView.getPrintStream());
		show(mode);

		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		getContentPane().add(diffView);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				if (worker != null)
					worker.interrupt();
			}
		});
		pack();
	}

	/**
	 * Switch to a different diff mode.
	 * 
	 * @param mode
	 *            the mode to diff to
	 */
	protected void show(final Mode mode) {
		show(new Runnable() {
			@Override
			public void run() {
				try {
					diff.showDiff(filename, remote, local, mode);
				} catch (MalformedURLException e) {
					log.error(e);
				} catch (IOException e) {
					log.error(e);
				}
			}
		});
	}

	/**
	 * Show a different diff.
	 * 
	 * @param runnable
	 *            the object printing to the {@link DiffView}
	 */
	protected synchronized void show(final Runnable runnable) {
		if (worker != null)
			worker.interrupt();
		else
			diffView.setCursor(waitCursor);
		worker = new Thread() {
			@Override
			public void run() {
				try {
					clearDiff();
					runnable.run();
				} catch (RuntimeException e) {
					if (!(e.getCause() instanceof InterruptedException))
						log.error(e);
				} catch (Error e) {
					log.error(e);
				}
				diffView.setCursor(normalCursor);
				synchronized(DiffFile.this) {
					worker = null;
				}
			}
		};
		worker.start();
	}

	/**
	 * Remove the previous diff output from the {@link DiffView}.
	 */
	protected void clearDiff() {
		final Document doc = diffView.getDocument();
		try {
			doc.remove(diffOffset, doc.getLength() - diffOffset);
		} catch (BadLocationException e) {
			log.error(e);
		}
	}

	/**
	 * Add the action links for the available diff modes.
	 */
	private void addModeLinks() {
		for (final Mode mode : Mode.values()) {
			if (diffView.getDocument().getLength() > 0)
				diffView.normal(" ");
			diffView.link(mode.toString(), new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					show(mode);
				}
			});
		}
	}

	/**
	 * Add an action link to show the Git log of the file.
	 * 
	 * This method only adds the link if it can determine where the source for
	 * this component lives.
	 * 
	 * @param files
	 *            the file collection including information where the file lives
	 * @param fileObject
	 *            the component to inspect
	 */
	private void addGitLogLink(final FilesCollection files, final FileObject fileObject) {
		// first, we need to find Implementation-Build entries in the respective manifests
		final String commitLocal = getCommit(local);
		if (commitLocal == null) return;
		final String commitRemote = getCommit(remote);
		if (commitRemote == null) return;

		// now, let's find the .git/ directory.
		File directory = files.prefix(".");
		while (!new File(directory, ".git").exists()) {
			directory = directory.getParentFile();
			if (directory == null) return;
		}
		final File gitWorkingDirectory = directory;

		// now, let's find the directory where the first source of the local .jar is stored
		final String relativePath = findSourceDirectory(gitWorkingDirectory, local);
		if (relativePath == null) return;

		if (diffView.getDocument().getLength() > 0)
			diffView.normal(" ");
		diffView.link("Git log", new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				show(new Runnable() {
					@Override
					public void run() {
						final PrintStream out = diffView.getPrintStream();
						out.println();
						FileUtils.exec(gitWorkingDirectory,  out, out, "git", "log", "-p", commitRemote + ".." + commitLocal, "--", relativePath);
					}
				});
			}
		});
	}

	/**
	 * Given a {@link URL} to a <i>.jar</i> file, extract the Implementation-Build entry from the manifest.
	 * 
	 * @param jarURL the URL to the <i>.jar</i> file
	 */
	private static String getCommit(final URL jarURL) {
		try {
			final JarInputStream in = new JarInputStream(jarURL.openStream());
			Manifest manifest = in.getManifest();
			if (manifest == null)
				for (;;) {
					final JarEntry entry = in.getNextJarEntry();
					if (entry == null) return null;
					if (entry.getName().equals("META-INF/MANIFEST.MF")) {
						manifest = new Manifest(in);
						break;
					}
				}
			final Attributes attributes = manifest.getMainAttributes(); 
			return attributes.getValue(new Attributes.Name("Implementation-Build"));
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Given a {@link URL} to a <i>.jar</i> file, extract the path of the first <i>.class</i> file contained therein.
	 * 
	 * @param jarURL the URL to the <i>.jar</i> file
	 * @return the path stored in the <i>.jar</i> file
	 */
	private static String findSourceDirectory(final File gitWorkingDirectory, final URL jarURL) {
		try {
			final JarInputStream in = new JarInputStream(jarURL.openStream());
			for (;;) {
				final JarEntry entry = in.getNextJarEntry();
				if (entry == null) break;
				String path = entry.getName();
				if (!path.endsWith(".class")) continue;
				final ByteCodeAnalyzer analyzer = Diff.analyzeByteCode(in, false);
				final String sourceFile = analyzer.getSourceFile();
				if (sourceFile == null) continue;
				final String suffix = path.substring(0, path.lastIndexOf('/') + 1) + sourceFile;
				try {
					path = FileUtils.exec(gitWorkingDirectory, null, null, "git", "ls-files", "*/" + suffix);
					if (path.endsWith("\n")) path = path.substring(0, path.length() - 1);
				} catch (RuntimeException e) {
					/* ignore */
					continue;
				}
				if (path.indexOf('\n') >= 0) continue; // ls-files found multiple files
				path = path.substring(0, path.length() - suffix.length());
				if ("".equals(path)) path = ".";
				else if (path.endsWith("/src/main/java/")) path = path.substring(0, path.length() - "/src/main/java/".length());
				return path;
			}
		} catch (IOException e) { /* ignore */ e.printStackTrace(); }
		return null;
	}
}
