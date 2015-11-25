package org.jenkinsci.plugins.bitbucketNotifier;

/**
 * States communicated to the Bitbucket server.
 */
public enum BitbucketBuildState {
	SUCCESSFUL,
	FAILED,
	INPROGRESS,
}
