package ru.concerteza.util.buildnumber;

import java.io.File;
import java.util.List;
import java.util.Properties;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Goal which creates build number.
 */
@Mojo(name = "extract-buildnumber", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class JGitBuildNumberMojo extends AbstractMojo {
    /**
     * Revision property name
     *
     */
    @Parameter(property = "revisionProperty")
    private String revisionProperty = "git.revision";
    /**
     * Short revision property name
     *
     */
    @Parameter(property = "shortRevisionProperty")
    private String shortRevisionProperty = "git.shortRevision";

    /**
     * Branch property name
     *
     */
    @Parameter(property = "branchProperty")
    private String branchProperty = "git.branch";
    /**
     * Tag property name
     *
     */
    @Parameter(property = "tagProperty")
    private String tagProperty = "git.tag";
    /**
     * Parent property name
     *
     */
    @Parameter(property = "parentProperty")
    private String parentProperty = "git.parent";
    /**
     * Commits count property name
     *
     */
    @Parameter(property = "commitsCountProperty")
    private String commitsCountProperty = "git.commitsCount";
    /**
     * Buildnumber property name
     *
     */
    @Parameter(property = "buildnumberProperty")
    private String buildnumberProperty = "git.buildnumber";
    /**
     * authorDate property name
     *
     */
    @Parameter(property = "authorDateProperty")
    private String authorDateProperty = "git.authorDate";
    /**
     * commitDate property name
     *
     */
    @Parameter(property = "commitDateProperty")
    private String commitDateProperty = "git.commitDate";
    /**
     * Java Script buildnumber callback
     *
     */
    @Parameter(property = "javaScriptBuildnumberCallback")
    private String javaScriptBuildnumberCallback = null;
    /**
     * Setting this parameter to 'false' allows to execute plugin in every
     * submodule, not only in root one.
     *
     */
    @Parameter(property = "runOnlyAtExecutionRoot", defaultValue = "true")
    private boolean runOnlyAtExecutionRoot;
    /**
     * Directory to start searching git root from, should contain '.git' directory
     * or be a subdirectory of such directory. '${project.basedir}' is used by default.
     */
    @Parameter(property = "repositoryDirectory", defaultValue = "${project.basedir}")
    private File repositoryDirectory;

    @Parameter(property = "project.basedir", readonly = true, required = true)
    private File baseDirectory;

    @Parameter(property = "session.executionRootDirectory", readonly = true, required = true)
    private File executionRootDirectory;
    /**
     * The maven project.
     */
    @Parameter(property = "project", readonly = true)
    private MavenProject project;
     /**
     * The maven parent project.
     */
    @Parameter(property = "project.parent", readonly = true)
    private MavenProject parentProject;

    /**
     * Extracts buildnumber fields from git repository and publishes them as maven properties.
     * Executes only once per build. Return default (unknown) buildnumber fields on error.
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Properties props = project.getProperties();
        try {
            // executes only once per build
            // http://www.sonatype.com/people/2009/05/how-to-make-a-plugin-run-once-during-a-build/
            if (executionRootDirectory.equals(baseDirectory) || !runOnlyAtExecutionRoot) {
                // build started from this projects root
                BuildNumber bn = BuildNumberExtractor.extract(repositoryDirectory);
                props.setProperty(revisionProperty, bn.getRevision());
                props.setProperty(shortRevisionProperty, bn.getShortRevision());
                props.setProperty(branchProperty, bn.getBranch());
                props.setProperty(tagProperty, bn.getTag());
                props.setProperty(parentProperty, bn.getParent());
                props.setProperty(commitsCountProperty, bn.getCommitsCountAsString());
                props.setProperty(authorDateProperty, bn.getAuthorDate());
				props.setProperty(commitDateProperty, bn.getCommitDate());
                // create composite buildnumber
                String composite = createBuildnumber(bn);
                props.setProperty(buildnumberProperty, composite);
                getLog().info("Git info extracted, revision: '" + bn.getShortRevision() + "', branch: '" + bn.getBranch() +
                        "', tag: '" + bn.getTag() + "', commitsCount: '" + bn.getCommitsCount() + "', authorDate: '" + bn.getAuthorDate() + "', commitDate: '" + bn.getCommitDate() + "', buildnumber: '" + composite + "'");
            } else if("pom".equals(parentProject.getPackaging())) {
                // build started from parent, we are in subproject, lets provide parent properties to our project
                Properties parentProps = parentProject.getProperties();
                String revision = parentProps.getProperty(revisionProperty);
                if(null == revision) {
                    // we are in subproject, but parent project wasn't build this time,
                    // maybe build is running from parent with custom module list - 'pl' argument
                    getLog().info("Cannot extract Git info, maybe custom build with 'pl' argument is running");
                    fillPropsUnknown(props);
                    return;
                }
                props.setProperty(revisionProperty, revision);
                props.setProperty(shortRevisionProperty, parentProps.getProperty(shortRevisionProperty));
                props.setProperty(branchProperty, parentProps.getProperty(branchProperty));
                props.setProperty(tagProperty, parentProps.getProperty(tagProperty));
                props.setProperty(parentProperty, parentProps.getProperty(parentProperty));
                props.setProperty(commitsCountProperty, parentProps.getProperty(commitsCountProperty));
                props.setProperty(buildnumberProperty, parentProps.getProperty(buildnumberProperty));
                props.setProperty(authorDateProperty, parentProps.getProperty(authorDateProperty));
				props.setProperty(commitDateProperty, parentProps.getProperty(commitDateProperty));
            } else {
                // should not happen
                getLog().warn("Cannot extract JGit version: something wrong with build process, we're not in parent, not in subproject!");
                fillPropsUnknown(props);
            }
        } catch (Exception e) {
            getLog().error(e);
            fillPropsUnknown(props);
        }
    }

    private void fillPropsUnknown(Properties props) {
        props.setProperty(revisionProperty, "UNKNOWN_REVISION");
        props.setProperty(shortRevisionProperty, "UNKNOWN_REVISION");
        props.setProperty(branchProperty, "UNKNOWN_BRANCH");
        props.setProperty(tagProperty, "UNKNOWN_TAG");
        props.setProperty(parentProperty, "UNKNOWN_PARENT");
        props.setProperty(commitsCountProperty, "-1");
        props.setProperty(buildnumberProperty, "UNKNOWN_BUILDNUMBER");
        props.setProperty(authorDateProperty, "UNKNOWN_AUTHOR_DATE");
        props.setProperty(commitDateProperty, "UNKNOWN_COMMIT_DATE");
    }

    private String createBuildnumber(BuildNumber bn) throws ScriptException {
        if(null != javaScriptBuildnumberCallback) return buildnumberFromJS(bn);
        return bn.defaultBuildnumber();
    }

    private String buildnumberFromJS(BuildNumber bn) throws ScriptException {
        String engineName = "JavaScript";
        ScriptEngine jsEngine = new ScriptEngineManager().getEngineByName(engineName);
        if (jsEngine == null) {
            jsEngine = new ScriptEngineManager(null).getEngineByName(engineName);
        }
        jsEngine.put("tag", bn.getTag());
        jsEngine.put("branch", bn.getBranch());
        jsEngine.put("revision", bn.getRevision());
        jsEngine.put("parent", bn.getParent());
        jsEngine.put("shortRevision", bn.getShortRevision());
        jsEngine.put("commitsCount", bn.getCommitsCount());
        jsEngine.put("authorDate", bn.getAuthorDate());
        jsEngine.put("commitDate", bn.getCommitDate());
        Object res = jsEngine.eval(javaScriptBuildnumberCallback);
        if(null == res) throw new IllegalStateException("JS buildnumber callback returns null");
        return res.toString();
    }
}
