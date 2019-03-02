package com.labun.buildnumber;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.RevWalkException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/** Extracts buildnumber fields from git repository. */
public class BuildNumberExtractor {
    private static final String EMPTY_STRING = "";

    File gitDir;
    Git git;
    Repository repo;

    ObjectId headObjectId;
    String headSha1;
    String headSha1Short;

    boolean gitStatusDirty;

    /** initializes values which are always required, regardless of full or incremental build */
    public BuildNumberExtractor(File repoDirectory) throws Exception {
        if(!(repoDirectory.exists() && repoDirectory.isDirectory())) throw new IOException(
                "Invalid repository directory provided: " + repoDirectory.getAbsolutePath());

        // (previously, jgit had some problems with not canonical paths; is it still the case?)
        File canonicalRepo = repoDirectory.getCanonicalFile();
        RepositoryBuilder builder = new RepositoryBuilder().findGitDir(canonicalRepo);

        gitDir = builder.getGitDir();
        git = Git.open(gitDir);
        repo = git.getRepository();

        Ref headRef = repo.exactRef(Constants.HEAD);
        if (headRef == null) throw new IOException("Cannot read current revision from repository: " + repo);

        headObjectId = headRef.getObjectId();
        headSha1 = headObjectId.name();
        headSha1Short = abbreviateSha1(headSha1);

        // long t = System.currentTimeMillis();
        gitStatusDirty = !git.status().call().isClean();
        // System.out.println("dirty: " + gitStatusDirty + " (" + (System.currentTimeMillis() - t) + " ms)");
    }

    @Override
    protected void finalize() throws Throwable {
        git.close(); // also closes the `repo`
    }

    public String getHeadSha1() { return headSha1; }
    public String getHeadSha1Short() { return headSha1Short; }
    public boolean isGitStatusDirty() { return gitStatusDirty; }

    public Map<String, String> extract(String gitDateFormat, String buildDateFormat, String dateFormatTimeZone, String countCommitsSinceInclusive, String countCommitsSinceExclusive, String dirtyValue) throws Exception {
        try (RevWalk revWalk = new RevWalk(repo)) {
            String branch = readCurrentBranch(repo, headSha1);
            String tag = readTag(repo, headSha1);

            RevCommit headCommit = revWalk.parseCommit(headObjectId);

            String parent = readParent(headCommit);
            int commitsCount = countCommits(repo, headCommit, countCommitsSinceInclusive, countCommitsSinceExclusive);

            DateFormat dfGitDate = new SimpleDateFormat(gitDateFormat); // default locale
            if (dateFormatTimeZone != null) dfGitDate.setTimeZone(TimeZone.getTimeZone(dateFormatTimeZone));
            String authorDate = dfGitDate.format(headCommit.getAuthorIdent().getWhen());
            String commitDate = dfGitDate.format(headCommit.getCommitterIdent().getWhen());

            String describe = git.describe().setLong(true).call();

            SimpleDateFormat dfBuildDate = new SimpleDateFormat(buildDateFormat);
            if (dateFormatTimeZone != null) dfBuildDate.setTimeZone(TimeZone.getTimeZone(dateFormatTimeZone));
            String buildDate = dfBuildDate.format(new Date());

            String revision = headSha1;
            String shortRevision = abbreviateSha1(headSha1);
            String dirty = gitStatusDirty ? dirtyValue : "";
            String commitsCountAsString = Integer.toString(commitsCount);

            String buildnumber = defaultBuildnumber(tag, branch, commitsCountAsString, shortRevision, dirty);

            Map<String, String> res = new HashMap<>();
            res.put("revision", revision);
            res.put("shortRevision", shortRevision);
            res.put("dirty", dirty);
            res.put("branch", branch);
            res.put("tag", tag);
            res.put("parent", parent);
            res.put("commitsCount", commitsCountAsString);
            res.put("authorDate", authorDate);
            res.put("commitDate", commitDate);
            res.put("describe", describe);
            res.put("buildDate", buildDate);
            res.put("buildnumber", buildnumber);

            return res;
        }

    }

    private String abbreviateSha1(String sha1) {
        return (sha1 != null && sha1.length() > 7) ? sha1.substring(0, 7) : sha1;
    }

    public String defaultBuildnumber(String tag, String branch, String commitsCount, String shortRevision, String dirty) {
        String name = (tag.length() > 0) ? tag : (branch.length() > 0) ? branch : "UNNAMED";
        return name + "." + commitsCount + "." + shortRevision + (dirty.length() > 0 ? "-" + dirty : "");
    }

    private static String readCurrentBranch(Repository repo, String headSha1) throws IOException {
        String branch = repo.getBranch();
        // should not happen
        if (null == branch) return EMPTY_STRING;
        if (headSha1.equals(branch)) return EMPTY_STRING;
        return branch;
    }

    private static String readTag(Repository repo, String sha1) {
        Map<String, String> tagMap = loadTagsMap(repo);
        String tag = tagMap.get(sha1);
        if (null == tag) return EMPTY_STRING;
        return tag;
    }

    private static String readParent(RevCommit commit) throws IOException {
        if (commit == null) return EMPTY_STRING;
        RevCommit[] parents = commit.getParents();
        if (null == parents || parents.length == 0) return EMPTY_STRING;
        String parentsFormat = null;
        for (RevCommit p : parents) {
            String sha1 = p.getId().name();
            if (null == parentsFormat) {
                parentsFormat = sha1;
            } else {
                parentsFormat += ";" + sha1;
            }
        }
        return parentsFormat;
    }

    // sha1 -> tag name
    private static Map<String, String> loadTagsMap(Repository repo) {
        Map<String, Ref> refMap = repo.getTags();
        Map<String, String> res = new HashMap<String, String>(refMap.size());
        for (Map.Entry<String, Ref> en : refMap.entrySet()) {
            String sha1 = extractPeeledSha1(repo, en.getValue());
            String existed = res.get(sha1);
            final String value = (existed == null) ? en.getKey() : existed + ";" + en.getKey();
            res.put(sha1, value);
        }
        return res;
    }

    // search for sha1 corresponding to annotated tag
    private static String extractPeeledSha1(Repository repo, Ref ref) {
        Ref peeled = repo.peel(ref);
        ObjectId oid = peeled.getPeeledObjectId();
        return null != oid ? oid.name() : peeled.getObjectId().name();
    }

    private int countCommits(Repository repo, RevCommit headCommit, String countCommitsSinceInclusive, String countCommitsSinceExclusive) throws Exception {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRetainBody(false);
            walk.markStart(headCommit);
            int res = 0;
            if (countCommitsSinceInclusive != null) {
                String ancestorSha1 = getSha1(countCommitsSinceInclusive);
                for (RevCommit commit : walk) { res += 1; if (commit.getId().getName().startsWith(ancestorSha1)) break; }
            } else if (countCommitsSinceExclusive != null) {
                String ancestorSha1 = getSha1(countCommitsSinceExclusive);
                for (RevCommit commit : walk) { if (commit.getId().getName().startsWith(ancestorSha1)) break; res += 1; }
            } else {
                for (RevCommit commit : walk) { res += 1; }
            }
            return res;
        } catch (RevWalkException ex) {
            // ignore exception thrown by JGit when walking shallow clone, return -1 to indicate shallow
            return -1;
        }
    }

    /** If the parameter is a tag, returns SHA-1 of the commit it points to; otherwise returns the parameter unchanged. 
     * @param tagOrSha1 tag (annotated or lightweight) or SHA-1 (complete or abbreviated) 
     * @return SHA-1 (complete or abbreviated) */
    private String getSha1(String tagOrSha1) throws Exception {
        Ref ref = repo.exactRef(Constants.R_TAGS + tagOrSha1);
        return (ref != null)? ref.getPeeledObjectId().name() : tagOrSha1;
    }

}