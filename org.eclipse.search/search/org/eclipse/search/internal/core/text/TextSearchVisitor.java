/*******************************************************************************
 * Copyright (c) 2000, 2022 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Terry Parker <tparker@google.com> (Google Inc.) - Bug 441016 - Speed up text search by parallelizing it using JobGroups
 *     Sergey Prigogin (Google) - Bug 489551 - File Search silently drops results on StackOverflowError
 *******************************************************************************/
package org.eclipse.search.internal.core.text;

import java.io.CharConversionException;
import java.io.IOException;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobGroup;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;

import org.eclipse.jface.text.IDocument;

import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.texteditor.ITextEditor;

import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;
import org.eclipse.search.internal.core.text.FileCharSequenceProvider.FileCharSequenceException;
import org.eclipse.search.internal.ui.Messages;
import org.eclipse.search.internal.ui.SearchMessages;
import org.eclipse.search.internal.ui.SearchPlugin;
import org.eclipse.search.ui.NewSearchUI;

/**
 * The visitor that does the actual work.
 */
public class TextSearchVisitor {

	public static final boolean TRACING= "true".equalsIgnoreCase(Platform.getDebugOption("org.eclipse.search/perf")); //$NON-NLS-1$ //$NON-NLS-2$
	private static final int NUMBER_OF_LOGICAL_THREADS= Runtime.getRuntime().availableProcessors();

	/**
	 * Queue of files to be searched. IFile pointing to the same local file are
	 * grouped together
	 **/
	private final Queue<List<IFile>> fileBatches;

	public static class ReusableMatchAccess extends TextSearchMatchAccess {

		private int fOffset;
		private int fLength;
		private IFile fFile;
		private CharSequence fContent;

		public void initialize(IFile file, int offset, int length, CharSequence content) {
			fFile= file;
			fOffset= offset;
			fLength= length;
			fContent= content;
		}

		@Override
		public IFile getFile() {
			return fFile;
		}

		@Override
		public int getMatchOffset() {
			return fOffset;
		}

		@Override
		public int getMatchLength() {
			return fLength;
		}

		@Override
		public int getFileContentLength() {
			return fContent.length();
		}

		@Override
		public char getFileContentChar(int offset) {
			return fContent.charAt(offset);
		}

		@Override
		public String getFileContent(int offset, int length) {
			return fContent.subSequence(offset, offset + length).toString(); // must pass a copy!
		}
	}

	/**
	 * A JobGroup for text searches across multiple files.
	 */
	private static class TextSearchJobGroup extends JobGroup {
		public TextSearchJobGroup(String name, int maxThreads, int initialJobCount) {
			super(name, maxThreads, initialJobCount);
		}

		// Always continue processing all other files, even if errors are encountered in individual files.
		@Override
		protected boolean shouldCancel(IStatus lastCompletedJobResult, int numberOfFailedJobs, int numberOfCancelledJobs) {
			return false;
		}
	}

	/**
	 * A job to find matches in a set of files.
	 */
	private class TextSearchJob extends Job {
		private final Map<IFile, IDocument> fDocumentsInEditors;
		private FileCharSequenceProvider fileCharSequenceProvider;
		private final int jobCount;

		/**
		 * Searches for matches in the files.
		 *
		 * @param documentsInEditors
		 *            a map from IFile to IDocument for all open, dirty editors
		 * @param jobCount
		 *            number of Jobs
		 */
		public TextSearchJob(Map<IFile, IDocument> documentsInEditors, int jobCount) {
			super("File Search Worker"); //$NON-NLS-1$
			this.jobCount = jobCount;
			setSystem(true);
			fDocumentsInEditors= documentsInEditors;
		}

		@Override
		protected IStatus run(IProgressMonitor inner) {
			MultiStatus multiStatus=
					new MultiStatus(NewSearchUI.PLUGIN_ID, IStatus.OK, SearchMessages.TextSearchEngine_statusMessage, null);
			SubMonitor subMonitor = SubMonitor.convert(inner, fileBatches.size() / jobCount); // approximate
			this.fileCharSequenceProvider= new FileCharSequenceProvider();
			List<IFile> sameFiles;
			while (((sameFiles = fileBatches.poll()) != null) && !fFatalError) {
				IStatus status = processFile(sameFiles, subMonitor.split(1));
				// Only accumulate interesting status
				if (!status.isOK())
					multiStatus.add(status);
				// Group cancellation is propagated to this job's monitor.
				// Stop processing and return the status for the completed jobs.
			}
			fileCharSequenceProvider= null;
			return multiStatus;
		}

		public IStatus processFile(List<IFile> sameFiles, IProgressMonitor monitor) {
			// A natural cleanup after the change to use JobGroups is accepted would be to move these
			// methods to the TextSearchJob class.
			Matcher matcher= fSearchPattern.pattern().isEmpty() ? null : fSearchPattern.matcher(""); //$NON-NLS-1$
			IFile file = sameFiles.remove(0);
			monitor.setTaskName(file.getFullPath().toString());
			try {
				if (!fCollector.acceptFile(file) || matcher == null) {
					return Status.OK_STATUS;
				}

				List<TextSearchMatchAccess> occurences;
				CharSequence charsequence;

				IDocument document= getOpenDocument(file, getDocumentsInEditors());
				if (document != null) {
					charsequence = new DocumentCharSequence(document);
					// assume all documents are non-binary
					occurences = locateMatches(file, charsequence, matcher, monitor);
				} else {
					try {
						charsequence = fileCharSequenceProvider.newCharSequence(file);
						if (hasBinaryContent(charsequence, file) && !fCollector.reportBinaryFile(file)) {
							return Status.OK_STATUS;
						}
						occurences = locateMatches(file, charsequence, matcher, monitor);
					} catch (FileCharSequenceProvider.FileCharSequenceException e) {
						throw (RuntimeException) e.getCause();
					}
				}
				fCollector.flushMatches(file);

				for (IFile duplicateFiles : sameFiles) {
					// reuse previous result
					ReusableMatchAccess matchAccess= new ReusableMatchAccess();
					for (TextSearchMatchAccess occurence : occurences) {
						matchAccess.initialize(duplicateFiles, occurence.getMatchOffset(), occurence.getMatchLength(),
								charsequence);
						boolean goOn= fCollector.acceptPatternMatch(matchAccess);
						if (!goOn) {
							break;
						}
					}
					fCollector.flushMatches(duplicateFiles);
				}
				if (document == null) {
					try {
						fileCharSequenceProvider.releaseCharSequence(charsequence);
					} catch (IOException e) {
						SearchPlugin.log(e);
					}
				}
			} catch (UnsupportedCharsetException e) {
				String[] args= { getCharSetName(file), file.getFullPath().makeRelative().toString()};
				String message= Messages.format(SearchMessages.TextSearchVisitor_unsupportedcharset, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (IllegalCharsetNameException e) {
				String[] args= { getCharSetName(file), file.getFullPath().makeRelative().toString()};
				String message= Messages.format(SearchMessages.TextSearchVisitor_illegalcharset, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (IOException e) {
				String[] args= { getExceptionMessage(e), file.getFullPath().makeRelative().toString()};
				String message= Messages.format(SearchMessages.TextSearchVisitor_error, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (CoreException e) {
				if (fIsLightweightAutoRefresh && IResourceStatus.RESOURCE_NOT_FOUND == e.getStatus().getCode()) {
					return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
				}
				String[] args= { getExceptionMessage(e), file.getFullPath().makeRelative().toString() };
				String message= Messages.format(SearchMessages.TextSearchVisitor_error, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (StackOverflowError e) {
				fFatalError= true;
				String message= SearchMessages.TextSearchVisitor_patterntoocomplex0;
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} finally {
				synchronized (fLock) {
					fCurrentFile= file;
					fNumberOfScannedFiles++;
				}
			}
			if (monitor.isCanceled()) {
				fFatalError = true;
				return Status.CANCEL_STATUS;
			}
			return Status.OK_STATUS;
		}

		public Map<IFile, IDocument> getDocumentsInEditors() {
			return fDocumentsInEditors;
		}

	}


	private final TextSearchRequestor fCollector;
	private final Pattern fSearchPattern;

	private IProgressMonitor fProgressMonitor;

	private int fNumberOfFilesToScan;
	private int fNumberOfScannedFiles;  // Protected by fLock
	private IFile fCurrentFile;  // Protected by fLock
	private Object fLock= new Object();

	private final MultiStatus fStatus;
	private volatile boolean fFatalError; // If true, terminates the search.

	private boolean fIsLightweightAutoRefresh;

	public TextSearchVisitor(TextSearchRequestor collector, Pattern searchPattern) {
		fCollector= collector;
		fStatus= new MultiStatus(NewSearchUI.PLUGIN_ID, IStatus.OK, SearchMessages.TextSearchEngine_statusMessage, null);

		fSearchPattern= searchPattern;

		fIsLightweightAutoRefresh= Platform.getPreferencesService().getBoolean(ResourcesPlugin.PI_RESOURCES, ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, false, null);
		fileBatches = new ConcurrentLinkedQueue<>();
	}

	public IStatus search(IFile[] files, IProgressMonitor monitor) {
		if (files.length == 0) {
			return fStatus;
		}
		fProgressMonitor= monitor == null ? new NullProgressMonitor() : monitor;
		fNumberOfScannedFiles= 0;
		fNumberOfFilesToScan= files.length;
		fCurrentFile= null;

		int threadsNeeded = Math.min(files.length, NUMBER_OF_LOGICAL_THREADS);
		// All but 1 threads should search. 1 thread does the UI updates:
		int jobCount = fCollector.canRunInParallel() && threadsNeeded > 1 ? threadsNeeded - 1 : 1;

		// Seed count over 1 can cause endless waits, see bug 543629 comment 2
		// TODO use seed = jobCount after the bug 543660 in JobGroup is fixed
		final int seed = 1;
		final JobGroup jobGroup = new TextSearchJobGroup("Text Search", jobCount, seed); //$NON-NLS-1$
		long startTime= TRACING ? System.currentTimeMillis() : 0;

		Job monitorUpdateJob= new Job(SearchMessages.TextSearchVisitor_progress_updating_job) {

			@Override
			public IStatus run(IProgressMonitor inner) {
				int fLastNumberOfScannedFiles = 0;
				try {
					while (!inner.isCanceled()) {
						// Propagate user cancellation to the JobGroup.
						if (fProgressMonitor.isCanceled()) {
							break;
						}

						IFile file;
						int numberOfScannedFiles;
						synchronized (fLock) {
							file = fCurrentFile;
							numberOfScannedFiles = fNumberOfScannedFiles;
						}
						if (numberOfScannedFiles == fNumberOfFilesToScan) {
							return Status.OK_STATUS;
						}
						if (file != null) {
							String fileName = file.getName();
							Object[] args = { fileName, Integer.valueOf(numberOfScannedFiles),
									Integer.valueOf(fNumberOfFilesToScan) };
							fProgressMonitor.subTask(Messages.format(SearchMessages.TextSearchVisitor_scanning, args));
							int steps = numberOfScannedFiles - fLastNumberOfScannedFiles;
							fProgressMonitor.worked(steps);
							fLastNumberOfScannedFiles += steps;
						}
						try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							return Status.OK_STATUS;
						}
					}
					return Status.OK_STATUS;
				} finally {
					jobGroup.cancel();
				}
			}
		};

		try {
			String taskName= fSearchPattern.pattern().isEmpty()
					? SearchMessages.TextSearchVisitor_filesearch_task_label
					: ""; //$NON-NLS-1$
			fProgressMonitor.beginTask(taskName, fNumberOfFilesToScan);
			try {
				fCollector.beginReporting();
				Map<IFile, IDocument> documentsInEditors= PlatformUI.isWorkbenchRunning() ? evalNonFileBufferDocuments() : Collections.emptyMap();

				// group files with same content together:

				Map<String, List<IFile>> localFilesByLocation = new LinkedHashMap<>();
				Map<String, List<IFile>> remotFilesByLocation = new LinkedHashMap<>();

				if (!fProgressMonitor.isCanceled()) {
					for (IFile file : files) {
						IPath path = file.getLocation();
						String key = path == null ? file.getLocationURI().toString() : path.toString();
						Map<String, List<IFile>> filesByLocation = (path != null) ? localFilesByLocation
								: remotFilesByLocation;
						filesByLocation.computeIfAbsent(key, k -> new ArrayList<>()).add(file);

					}
				}
				localFilesByLocation.values().forEach(fileBatches::offer);
				remotFilesByLocation.values().forEach(fileBatches::offer);

				for (int i = 0; i < jobCount; i++) {
					Job job = new TextSearchJob(documentsInEditors, jobCount);
					job.setJobGroup(jobGroup);
					job.schedule();
				}
				monitorUpdateJob.setSystem(true);
				monitorUpdateJob.schedule();

				// The monitorUpdateJob is managing progress and cancellation,
				// so it is ok to pass a null monitor into the job group.
				try {
					// avoid stale jobs
					monitorUpdateJob.join(0, null);
				} catch (InterruptedException e) {
					// ignore
				}
				jobGroup.join(0, null); // XXX can deadlock, but using
										// fProgressMonitor shows wrong
										// progress. no guarantee
										// monitorUpdateJob is running in
										// another worker
				if (fProgressMonitor.isCanceled())
					throw new OperationCanceledException(SearchMessages.TextSearchVisitor_canceled);

				fStatus.addAll(jobGroup.getResult());
				return fStatus;
			} catch (InterruptedException e) {
				throw new OperationCanceledException(SearchMessages.TextSearchVisitor_canceled);
			} finally {
				fileBatches.clear();
			}
		} finally {
			fProgressMonitor.done();
			fCollector.endReporting();
			if (TRACING) {
				Object[] args= { Integer.valueOf(fNumberOfScannedFiles), Integer.valueOf(jobCount), Integer.valueOf(NUMBER_OF_LOGICAL_THREADS), Long.valueOf(System.currentTimeMillis() - startTime) };
				System.out.println(Messages.format(
						"[TextSearch] Search duration for {0} files in {1} jobs using {2} threads: {3}ms", args)); //$NON-NLS-1$
			}
		}
	}

	public IStatus search(TextSearchScope scope, IProgressMonitor monitor) {
		return search(scope.evaluateFilesInScope(fStatus), monitor);
	}

	/**
	 * Returns a map from IFile to IDocument for all open, dirty editors. After creation this map
	 * is not modified, so returning a non-synchronized map is ok.
	 *
	 * @return a map from IFile to IDocument for all open, dirty editors
	 */
	private Map<IFile, IDocument> evalNonFileBufferDocuments() {
		Map<IFile, IDocument> result= new HashMap<>();
		IWorkbench workbench = PlatformUI.getWorkbench();
		IWorkbenchWindow[] windows= workbench.getWorkbenchWindows();
		for (IWorkbenchWindow window : windows) {
			IWorkbenchPage[] pages= window.getPages();
			for (IWorkbenchPage page : pages) {
				IEditorReference[] editorRefs= page.getEditorReferences();
				for (IEditorReference editorRef : editorRefs) {
					IEditorPart ep= editorRef.getEditor(false);
					if (ep instanceof ITextEditor && ep.isDirty()) { // only dirty editors
						evaluateTextEditor(result, ep);
					}
				}
			}
		}
		return result;
	}

	private void evaluateTextEditor(Map<IFile, IDocument> result, IEditorPart ep) {
		IEditorInput input= ep.getEditorInput();
		if (input instanceof IFileEditorInput) {
			IFile file= ((IFileEditorInput) input).getFile();
			if (!result.containsKey(file)) { // take the first editor found
				ITextFileBufferManager bufferManager= FileBuffers.getTextFileBufferManager();
				ITextFileBuffer textFileBuffer= bufferManager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
				if (textFileBuffer != null) {
					// file buffer has precedence
					result.put(file, textFileBuffer.getDocument());
				} else {
					// use document provider
					IDocument document= ((ITextEditor) ep).getDocumentProvider().getDocument(input);
					if (document != null) {
						result.put(file, document);
					}
				}
			}
		}
	}

	private boolean hasBinaryContent(CharSequence seq, IFile file) throws CoreException {
		if (seq instanceof String) {
			if (!((String) seq).contains("\0")) { //$NON-NLS-1$
				// fail fast to avoid file.getContentDescription():
				return false;
			}
		}
		IContentDescription desc= file.getContentDescription();
		if (desc != null) {
			IContentType contentType= desc.getContentType();
			if (contentType != null && contentType.isKindOf(Platform.getContentTypeManager().getContentType(IContentTypeManager.CT_TEXT))) {
				return false;
			}
		}

		// avoid calling seq.length() at it runs through the complete file,
		// thus it would do so for all binary files.
		try {
			int limit= FileCharSequenceProvider.BUFFER_SIZE;
			for (int i= 0; i < limit; i++) {
				if (seq.charAt(i) == '\0') {
					return true;
				}
			}
		} catch (IndexOutOfBoundsException e) {
		} catch (FileCharSequenceException ex) {
			if (ex.getCause() instanceof CharConversionException)
				return true;
			throw ex;
		}
		return false;
	}

	private List<TextSearchMatchAccess> locateMatches(IFile file, CharSequence searchInput, Matcher matcher, IProgressMonitor monitor) throws CoreException {
		List<TextSearchMatchAccess> occurences= null;
		matcher.reset(searchInput);
		int k= 0;
		while (matcher.find()) {
			if (occurences == null) {
				occurences= new ArrayList<>();
			}
			int start= matcher.start();
			int end= matcher.end();
			if (end != start) { // don't report 0-length matches
				ReusableMatchAccess access= new ReusableMatchAccess();
				access.initialize(file, start, end - start, searchInput);
				occurences.add(access);
				boolean res= fCollector.acceptPatternMatch(access);
				if (!res) {
					return occurences; // no further reporting requested
				}
			}
			// Periodically check for cancellation and quit working on the current file if the job has been cancelled.
			if (k++ % 20 == 0 && monitor.isCanceled()) {
				break;
			}
		}
		if (occurences == null) {
			occurences= Collections.emptyList();
		}
		return occurences;
	}


	private String getExceptionMessage(Exception e) {
		String message= e.getLocalizedMessage();
		if (message == null) {
			return e.getClass().getName();
		}
		return message;
	}

	private IDocument getOpenDocument(IFile file, Map<IFile, IDocument> documentsInEditors) {
		IDocument document= documentsInEditors.get(file);
		if (document == null) {
			ITextFileBufferManager bufferManager= FileBuffers.getTextFileBufferManager();
			ITextFileBuffer textFileBuffer= bufferManager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
			if (textFileBuffer != null) {
				document= textFileBuffer.getDocument();
			}
		}
		return document;
	}

	private String getCharSetName(IFile file) {
		try {
			return file.getCharset();
		} catch (CoreException e) {
			return "unknown"; //$NON-NLS-1$
		}
	}

}
