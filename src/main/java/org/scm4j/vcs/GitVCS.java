package org.scm4j.vcs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffEntry.Side;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.VCSChangeType;
import org.scm4j.vcs.api.VCSCommit;
import org.scm4j.vcs.api.VCSDiffEntry;
import org.scm4j.vcs.api.VCSMergeResult;
import org.scm4j.vcs.api.VCSTag;
import org.scm4j.vcs.api.WalkDirection;
import org.scm4j.vcs.api.exceptions.EVCSBranchExists;
import org.scm4j.vcs.api.exceptions.EVCSException;
import org.scm4j.vcs.api.exceptions.EVCSFileNotFound;
import org.scm4j.vcs.api.exceptions.EVCSTagExists;
import org.scm4j.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;
import org.scm4j.vcs.api.workingcopy.IVCSWorkspace;

public class GitVCS implements IVCS {

	public static final String GIT_VCS_TYPE_STRING = "git";
	private static final String MASTER_BRANCH_NAME = "master";
	private static final String REFS_REMOTES_ORIGIN = Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/";
	private CredentialsProvider credentials;
	private final IVCSRepositoryWorkspace repo;
	
	public CredentialsProvider getCredentials() {
		return credentials;
	}
	
	public GitVCS(IVCSRepositoryWorkspace repo) {
		this.repo = repo;
	}
	
	public void setCredentials(CredentialsProvider credentials) {
		this.credentials = credentials;
	}
	
	private String getRealBranchName(String branchName) {
		return branchName == null ? MASTER_BRANCH_NAME : branchName;
	}
	
	protected Git getLocalGit(String folder) throws Exception {
		Repository gitRepo = new FileRepositoryBuilder()
				.setGitDir(new File(folder, ".git"))
				.build();
		Boolean repoInited = gitRepo
				.getObjectDatabase()
				.exists();
		if (!repoInited) {
			Git
					.cloneRepository()
					.setDirectory(new File(folder))
					.setURI(repo.getRepoUrl())
					.setCredentialsProvider(credentials)
					.setNoCheckout(true)
					.setCloneAllBranches(true)
					//.setBranch(Constants.R_HEADS + Constants.MASTER)
					.call()
					.close();
		}
		return new Git(gitRepo);
	}
	
	protected Git getLocalGit(IVCSLockedWorkingCopy wc) throws Exception {
		return getLocalGit(wc.getFolder().getPath());
	}
	
	public VCSChangeType gitChangeTypeToVCSChangeType(ChangeType changeType) {
		switch (changeType) {
		case ADD:
			return VCSChangeType.ADD;
		case DELETE:
			return VCSChangeType.DELETE;
		case MODIFY:
			return VCSChangeType.MODIFY;
		default:
			return VCSChangeType.UNKNOWN;
		}
	}

	@Override
	public void createBranch(String srcBranchName, String newBranchName, String commitMessage) {
		// note: no commit message could be attached in Git
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			checkout(git, gitRepo, srcBranchName, null);

			git
					.branchCreate()
					.setUpstreamMode(SetupUpstreamMode.TRACK)
					.setName(newBranchName)
					.call();

			RefSpec refSpec = new RefSpec().setSourceDestination(newBranchName,
					newBranchName);

			push(git, refSpec);
		} catch (RefAlreadyExistsException e) {
			throw new EVCSBranchExists (e);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} 
	}

	@Override
	public void deleteBranch(String branchName, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			checkout(git, gitRepo, MASTER_BRANCH_NAME, null);

			git
					.branchDelete()
					.setBranchNames(branchName)
					.setForce(true) // avoid "not merged" exception
					.call();

			RefSpec refSpec = new RefSpec( ":refs/heads/" + branchName);
			
			push(git, refSpec);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void push(Git git, RefSpec refSpec) throws GitAPIException {
		PushCommand cmd =  git
				.push();
		if (refSpec != null) {
			cmd.setRefSpecs(refSpec);
		} else {
			cmd.setPushAll();
		}
		cmd
				.setRemote("origin")
				.setCredentialsProvider(credentials)
				.call();
	}

	@Override
	public VCSMergeResult merge(String srcBranchName, String dstBranchName, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			checkout(git, gitRepo, dstBranchName, null);

			MergeResult mr = git
					.merge()
					.include(gitRepo.findRef("origin/" + getRealBranchName(srcBranchName)))
					.setMessage(commitMessage)
					.call();

			Boolean success =
					!mr.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING) &&
					!mr.getMergeStatus().equals(MergeResult.MergeStatus.FAILED) &&
					!mr.getMergeStatus().equals(MergeResult.MergeStatus.ABORTED) &&
					!mr.getMergeStatus().equals(MergeResult.MergeStatus.NOT_SUPPORTED);

			List<String> conflictingFiles = new ArrayList<>();
			if (!success) {
				conflictingFiles.addAll(mr.getConflicts().keySet());
				try {
					git
							.reset()
							.setMode(ResetType.HARD)
							.call();
				} catch(Exception e) {
					wc.setCorrupted(true);
				}
			} else {
				push(git, null);
			}
			return new VCSMergeResult(success, conflictingFiles);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void setCredentials(String user, String password) {
		setCredentials(new UsernamePasswordCredentialsProvider(user, password));
	}

	@Override
	public void setProxy(final String host, final int port, final String proxyUser, final String proxyPassword) {
		ProxySelector.setDefault(new ProxySelector() {
			
			final ProxySelector delegate = ProxySelector.getDefault();
			
			@Override
			public List<Proxy> select(URI uri) {
				if (uri.toString().toLowerCase().contains(repo.getRepoUrl().toLowerCase())) {
					return Collections.singletonList(new Proxy(Type.HTTP, InetSocketAddress
							.createUnresolved(host, port)));
				} else {
					return delegate == null ? Collections.singletonList(Proxy.NO_PROXY)
			                : delegate.select(uri);
				}
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				if (delegate != null) {
					delegate.connectFailed(uri, sa, ioe);
				}
			}
		});
		Authenticator.setDefault(new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				System.out.println(super.getRequestingSite().getHostName());
				System.out.println(repo.getRepoUrl());
				if (super.getRequestingSite().getHostName().contains(repo.getRepoUrl()) &&
						super.getRequestingPort() == port) {
					return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
				}
				return super.getPasswordAuthentication();
			}
		});
	}

	@Override
	public String getRepoUrl() {
		return repo.getRepoUrl(); 
	}
	
	private File getFileFromRepo(String branchName, String fileRelativePath) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			checkout(git, gitRepo, branchName, null);

			return new File(wc.getFolder(), fileRelativePath);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getFileContent(String branchName, String fileRelativePath, String encoding) {
		File file = getFileFromRepo(branchName, fileRelativePath);
		if (!file.exists()) {
			throw new EVCSFileNotFound(String.format("File %s is not found", fileRelativePath));
		}
		try {
			return IOUtils.toString(file.toURI(), encoding);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public VCSCommit setFileContent(String branchName, String filePath, String content, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {
			
			checkout(git, gitRepo, branchName, null);
			
			File file = new File(wc.getFolder(), filePath);
			if (!file.exists()) {
				FileUtils.forceMkdir(file.getParentFile());
				file.createNewFile();
				git
						.add()
						.addFilepattern(filePath)
						.call();
			}

			try (FileWriter fw = new FileWriter(file, false)) {
				fw.write(content);
			}

			RevCommit newCommit = git
					.commit()
					.setOnly(filePath)
					.setMessage(commitMessage)
					.call();

			String bn = getRealBranchName(branchName);
			RefSpec refSpec = new RefSpec(bn + ":" + bn);
			push(git, refSpec);
			return getVCSCommit(newCommit);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	void checkout(Git git, Repository gitRepo, String branchName, String revision) throws Exception {
		String bn = getRealBranchName(branchName);
		CheckoutCommand cmd = git.checkout();
		if (revision == null) {
			cmd
					.setStartPoint("origin/" + bn)
					.setCreateBranch(gitRepo.exactRef("refs/heads/" + bn) == null)
					.setUpstreamMode(SetupUpstreamMode.TRACK)
					.setName(bn)
					.call();
			git
					.pull()
					.call();
		} else {
			try (RevWalk walk = new RevWalk(gitRepo)) {
				RevCommit commit = walk.parseCommit(RevCommit.fromString(revision));
				// note: entering "detached HEAD" state here
				cmd
						.setName(commit.getName())
						.call();
			}
		}
	}

	@Override
	public String getFileContent(String branchName, String filePath) {
		return getFileContent(branchName, filePath, StandardCharsets.UTF_8.name());
	}
	
	@Override
	public List<VCSDiffEntry> getBranchesDiff(String srcBranchName, String dstBranchName) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk walk = new RevWalk(gitRepo)) {

			// https://stackoverflow.com/questions/34025577/jgit-how-to-show-changed-files-in-merge-commit

			String srcBN = getRealBranchName(srcBranchName);
			String dstBN = getRealBranchName(dstBranchName);

			RevCommit destHeadCommit = walk.parseCommit(git.getRepository().resolve("remotes/origin/" + dstBN));

			ObjectReader reader = gitRepo.newObjectReader();

			checkout(git, gitRepo, dstBranchName, null);

			git
					.merge()
					.include(gitRepo.findRef("origin/" + srcBN))
					.setCommit(false)
					.call();

			CanonicalTreeParser srcTreeIter = new CanonicalTreeParser();
			srcTreeIter.reset(reader, destHeadCommit.getTree());

			List<DiffEntry> diffs = git
					.diff()
					.setOldTree(srcTreeIter)
					.call();

			List<VCSDiffEntry> res = new ArrayList<>();
			for (DiffEntry diffEntry : diffs) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				try (DiffFormatter formatter = new DiffFormatter(baos)) {
					formatter.setRepository(git.getRepository());
					formatter.format(diffEntry);
				}
				VCSDiffEntry vcsEntry = new VCSDiffEntry(
						diffEntry.getPath(diffEntry.getChangeType() == ChangeType.ADD ? Side.NEW : Side.OLD),
						gitChangeTypeToVCSChangeType(diffEntry.getChangeType()), 
						baos.toString("UTF-8"));
				res.add(vcsEntry);
			}
			return res;
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Set<String> getBranches(String path) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {
			
			git
					.pull()
					.call();

			List<Ref> refs = git
					.branchList()
					.setListMode(ListMode.REMOTE)
					.call();
			Set<String> res = new HashSet<>();
			for (Ref ref : refs) {
				res.add(ref.getName().replace(REFS_REMOTES_ORIGIN, ""));
			}
			return res;
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<VCSCommit> log(String branchName, int limit) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {
				
			LogCommand log = git
					.log()
					.add(gitRepo.resolve("refs/remotes/origin/" + getRealBranchName(branchName)));

			if (limit > 0) {
				log.setMaxCount(limit);
			}
			
			Iterable<RevCommit> logs = log.call();
			
			List<VCSCommit> res = new ArrayList<>();
			for (RevCommit commit : logs) {
				res.add(getVCSCommit(commit));
			}
			
			return res;
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getVCSTypeString() {
		return GIT_VCS_TYPE_STRING;
	}

	@Override
	public VCSCommit removeFile(String branchName, String filePath, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			checkout(git, gitRepo, branchName, null);
			
			git
					.rm()
					.addFilepattern(filePath)
					.setCached(false)
					.call();

			RevCommit res = git
					.commit()
					.setMessage(commitMessage)
					.setAll(true)
					.call();

			push(git, null);
			return getVCSCommit(res);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected VCSCommit getVCSCommit(RevCommit revCommit) {
		return new VCSCommit(revCommit.getName(), revCommit.getFullMessage(), revCommit.getAuthorIdent().getName());
	}

	public List<VCSCommit> getCommitsRange(String branchName, String afterCommitId, String untilCommitId) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			checkout(git, gitRepo, branchName, null);

			String bn = getRealBranchName(branchName);

			ObjectId sinceCommit = afterCommitId == null ?
					getInitialCommit(gitRepo, bn).getId() :
					ObjectId.fromString(afterCommitId);

			ObjectId untilCommit = untilCommitId == null ?
					gitRepo.exactRef("refs/heads/" + bn).getObjectId() :
					ObjectId.fromString(untilCommitId);

			Iterable<RevCommit> commits;
			commits = git
					.log()
					.addRange(sinceCommit, untilCommit)
					.call();

			List<VCSCommit> res = new ArrayList<>();
			for (RevCommit commit : commits) {
				VCSCommit vcsCommit = getVCSCommit(commit);
				res.add(vcsCommit);
			}

			Collections.reverse(res);
			return res;
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private RevCommit getInitialCommit(Repository gitRepo, String branchName) throws Exception {
		try (RevWalk rw = new RevWalk(gitRepo)) {
			Ref ref = gitRepo.exactRef("refs/heads/" + branchName);
			ObjectId headCommitId = ref.getObjectId();
			RevCommit root = rw.parseCommit(headCommitId);
			rw.markStart(root);
			rw.sort(RevSort.REVERSE);
			return rw.next();
		}
	}

	@Override
	public IVCSWorkspace getWorkspace() {
		return repo.getWorkspace();
	}

	@Override
	public List<VCSCommit> getCommitsRange(String branchName, String startFromCommitId, WalkDirection direction,
			int limit) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			checkout(git, gitRepo, branchName, null);

			String bn = getRealBranchName(branchName);

			List<VCSCommit> res = new ArrayList<>();
			try (RevWalk rw = new RevWalk(gitRepo)) {
				RevCommit startCommit;
				RevCommit endCommit;
				if (direction == WalkDirection.ASC) {
					Ref ref = gitRepo.exactRef("refs/heads/" + bn);
					ObjectId headCommitId = ref.getObjectId();
					startCommit = rw.parseCommit( headCommitId );
					ObjectId sinceCommit = startFromCommitId == null ?
							getInitialCommit(gitRepo, bn).getId() :
							ObjectId.fromString(startFromCommitId);
					endCommit = rw.parseCommit(sinceCommit);
				} else {
					ObjectId sinceCommit = startFromCommitId == null ?
							gitRepo.exactRef("refs/heads/" + bn).getObjectId() :
							ObjectId.fromString(startFromCommitId);
					startCommit = rw.parseCommit( sinceCommit );
					endCommit = getInitialCommit(gitRepo, bn);
				}

				rw.markStart(startCommit);

				RevCommit commit = rw.next();
				while (commit != null) {
					VCSCommit vcsCommit = getVCSCommit(commit);
					res.add(vcsCommit);
					if (commit.getName().equals(endCommit.getName())) {
						break;
					}
					commit = rw.next();
				}
			}

			if (direction == WalkDirection.ASC) {
				Collections.reverse(res);
			}
			if (limit != 0) {
				res = res.subList(0, limit);
			}

			return res;
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private RevCommit getHeadRevCommit (String branchName) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {

			String bn = getRealBranchName(branchName);
			
			Ref ref = gitRepo.exactRef("refs/remotes/origin/" + bn);
			ObjectId commitId = ref.getObjectId();
			return rw.parseCommit( commitId );
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public VCSCommit getHeadCommit(String branchName) {
		RevCommit branchHeadCommit = getHeadRevCommit(getRealBranchName(branchName));
		return getVCSCommit(branchHeadCommit);
	}
	
	@Override
	public String toString() {
		return "GitVCS [url=" + repo.getRepoUrl() + "]";
	}

	@Override
	public Boolean fileExists(String branchName, String filePath) {
		return getFileFromRepo(branchName, filePath).exists();
	}

	@Override
	public VCSTag createTag(String branchName, String tagName, String tagMessage, String revisionToTag) throws EVCSTagExists {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {
			
			checkout(git, gitRepo, branchName, null);
			
			RevCommit commitToTag = revisionToTag == null ? null : rw.parseCommit(ObjectId.fromString(revisionToTag));
			
			Ref ref = git
					.tag()
					.setAnnotated(true)
					.setMessage(tagMessage)
					.setName(tagName)
					.setObjectId(commitToTag)
					.call();
			
			push(git, new RefSpec(ref.getName()));
			
			RevTag revTag = rw.parseTag(ref.getObjectId());
			RevCommit revCommit = rw.parseCommit(ref.getObjectId());
			VCSCommit relatedCommit = getVCSCommit(revCommit);
        	return new VCSTag(revTag.getTagName(), revTag.getFullMessage(), revTag.getTaggerIdent().getName(), relatedCommit);
		} catch(RefAlreadyExistsException e) {
			throw new EVCSTagExists(e);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<VCSTag> getTags() {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {
			
			List<Ref> tagRefs = getTagRefs();
	        List<VCSTag> res = new ArrayList<>();
	        RevCommit revCommit;
	        for (Ref ref : tagRefs) {
	        	ObjectId relatedCommitObjectId = ref.getPeeledObjectId() == null ? ref.getObjectId() : ref.getPeeledObjectId();
	        	revCommit = rw.parseCommit(relatedCommitObjectId);
	        	VCSCommit relatedCommit = getVCSCommit(revCommit);
	        	RevObject revObject = rw.parseAny(ref.getObjectId());
	        	VCSTag tag;
	        	if (revObject instanceof RevTag) {
	        		RevTag revTag = (RevTag) revObject;
	        		tag = new VCSTag(revTag.getTagName(), revTag.getFullMessage(), revTag.getTaggerIdent().getName(), relatedCommit);
	        	} else  {
	        		tag = new VCSTag(ref.getName().replace("refs/tags/", ""), null, null, relatedCommit);
	        	}
	        	res.add(tag);
	        }
	        return res;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	List<Ref> getTagRefs() throws Exception {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository()) {

			git.pull().call();
			
			List<Ref> refs = git
					.tagList()
					.call();

			return refs;
		}
	}

	@Override
	public VCSTag getLastTag() {
		List<Ref> tagRefs;
		try {
			tagRefs = getTagRefs();
			if (tagRefs.isEmpty()) {
				return null;
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {
            
			Collections.sort(tagRefs, new Comparator<Ref>() {
				public int compare(Ref o1, Ref o2) {
					try (Repository gitRepo = git.getRepository();
						 RevWalk rw = new RevWalk(gitRepo)) { // for exception rethrow test only
						Date d1 = rw.parseTag(o1.getObjectId()).getTaggerIdent().getWhen();
						Date d2 = rw.parseTag(o2.getObjectId()).getTaggerIdent().getWhen();
						return d1.compareTo(d2);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});

            Ref ref = tagRefs.get(tagRefs.size() - 1);
            RevCommit revCommit = rw.parseCommit(ref.getObjectId());
            VCSCommit relatedCommit = getVCSCommit(revCommit);
            if (git.getRepository().peel(ref).getPeeledObjectId() == null) {
            	return new VCSTag(ref.getName().replace("refs/tags/", ""), null, null, relatedCommit);
            }
        	RevTag revTag = rw.parseTag(ref.getObjectId());
        	return new VCSTag(revTag.getTagName(), revTag.getFullMessage(), revTag.getTaggerIdent().getName(), relatedCommit);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void removeTag(String tagName) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {
			
			checkout(git, gitRepo, MASTER_BRANCH_NAME, null);
			
			git
					.tagDelete()
					.setTags(tagName)
					.call();

			push(git, new RefSpec(":refs/tags/" + tagName));
			
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void checkout(String branchName, String targetPath, String revision)  {
		try (Git git = getLocalGit(targetPath);
			 Repository gitRepo = git.getRepository()) {
			
			checkout(git, gitRepo, branchName, revision);

		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Boolean isRevisionTagged(String revision) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {
			
			checkout(git, gitRepo, MASTER_BRANCH_NAME, null);
			List<Ref> tagRefs = getTagRefs();
	        for (Ref ref : tagRefs) {
	        	RevObject revObject = rw.parseAny(ref.getObjectId());
	        	if (revObject instanceof RevTag) {
	        		if (((RevTag) revObject).getObject().getName().equals(revision)) {
	        			return true;
	        		}
	        	} else {
	        		if (revObject.getName().equals(revision)) {
	        			return true;
	        		}
	        	}
	        }
	        return false;
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public VCSTag getTagByName(String tagName) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy();
			 Git git = getLocalGit(wc);
			 Repository gitRepo = git.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {
			
			git.pull().call();
			
			List<Ref> tagRefs = getTagRefs();
			RevCommit revCommit;
			for (Ref ref : tagRefs) {
				ObjectId relatedCommitObjectId = ref.getPeeledObjectId() == null ? ref.getObjectId() : ref.getPeeledObjectId();
	        	revCommit = rw.parseCommit(relatedCommitObjectId);
	        	VCSCommit relatedCommit = getVCSCommit(revCommit);
	        	RevObject revObject = rw.parseAny(ref.getObjectId());
	        	if (revObject instanceof RevTag) {
	        		RevTag revTag = (RevTag) revObject;
	        		if (revTag.getTagName().equals(tagName)) {
	        			return new VCSTag(revTag.getTagName(), revTag.getFullMessage(), revTag.getTaggerIdent().getName(), relatedCommit);
	        		}
	        	} else  {
	        		if (ref.getName().replace("refs/tags/", "").equals(tagName)) {
	        			return new VCSTag(ref.getName().replace("refs/tags/", ""), null, null, relatedCommit);
	        		}
	        	}
			}
			return null;
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
