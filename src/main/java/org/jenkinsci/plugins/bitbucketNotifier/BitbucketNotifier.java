/*
 * Copyright 2013 Georg Gruetter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package org.jenkinsci.plugins.bitbucketNotifier;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.*;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.*;
import hudson.plugins.git.GitBranchTokenMacro;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

/**
 * Notifies a configured Atlassian Bitbucket server instance of build results
 * through the Bitbucket build API.
 * <p>
 * Only basic authentication is supported at the moment.
 */
public class BitbucketNotifier extends Notifier {

	public static final int MAX_FIELD_LENGTH = 255;
	public static final int MAX_URL_FIELD_LENGTH = 450;

	// attributes --------------------------------------------------------------

	/** base url of Bitbucket server, e. g. <tt>http://localhost:7990</tt>. */
	private final String bitbucketServerBaseUrl;

	/** The id of the credentials to use. */
	private String credentialsId;

	/** if true, ignore exception thrown in case of an unverified SSL peer. */
	private final boolean ignoreUnverifiedSSLPeer;

	/** specify the commit from config */
	private final String commitSha1;

	/** if true, the build number is included in the Bitbucket notification. */
	private final boolean includeBuildNumberInKey;

	/** specify project key manually */
	private final String projectKey;

	/** append parent project key to key formation */
	private final boolean prependParentProjectKey;

	/** whether to send INPROGRESS notification at the build start */
	private final boolean disableInprogressNotification;

// public members ----------------------------------------------------------

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@DataBoundConstructor
	public BitbucketNotifier(
			String bitbucketServerBaseUrl,
			String credentialsId,
			boolean ignoreUnverifiedSSLPeer,
			String commitSha1,
			boolean includeBuildNumberInKey,
			String projectKey,
			boolean prependParentProjectKey,
			boolean disableInprogressNotification
	) {


		this.bitbucketServerBaseUrl = bitbucketServerBaseUrl.endsWith("/")
				? bitbucketServerBaseUrl.substring(0, bitbucketServerBaseUrl.length() - 1)
				: bitbucketServerBaseUrl;
		this.credentialsId = credentialsId;
		this.ignoreUnverifiedSSLPeer
				= ignoreUnverifiedSSLPeer;
		this.commitSha1 = commitSha1;
		this.includeBuildNumberInKey = includeBuildNumberInKey;
		this.projectKey = projectKey;
		this.prependParentProjectKey = prependParentProjectKey;
		this.disableInprogressNotification = disableInprogressNotification;
	}

	public boolean isDisableInprogressNotification() {
		return disableInprogressNotification;
	}

	public String getCredentialsId() {
		return credentialsId;
	}

	public String getBitbucketServerBaseUrl() {
		return bitbucketServerBaseUrl;
	}

	public boolean getIgnoreUnverifiedSSLPeer() {
		return ignoreUnverifiedSSLPeer;
	}

	public String getCommitSha1() {
		return commitSha1;
	}

	public boolean getIncludeBuildNumberInKey() {
		return includeBuildNumberInKey;
	}

    public String getProjectKey() {
        return projectKey;
    }

    public boolean getPrependParentProjectKey() {
        return prependParentProjectKey;
    }

    @Override
	public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
		    return disableInprogressNotification || processJenkinsEvent(build, listener, BitbucketBuildState.INPROGRESS);
	}

	@Override
	public boolean perform(
			AbstractBuild<?, ?> build,
			Launcher launcher,
			BuildListener listener) {

		if ((build.getResult() == null)
				|| (!build.getResult().equals(Result.SUCCESS))) {
			return processJenkinsEvent(
					build, listener, BitbucketBuildState.FAILED);
		} else {
			return processJenkinsEvent(
					build, listener, BitbucketBuildState.SUCCESSFUL);
		}
	}

	/**
	 * Processes the Jenkins events triggered before and after the build and
	 * initiates the Bitbucket notification.
	 *
	 * @param build		the build to notify Bitbucket of
	 * @param listener	the Jenkins build listener
	 * @param state		the state of the build (in progress, success, failed)
	 * @return			always true in order not to abort the Job in case of
	 * 					notification failures
	 */
	private boolean processJenkinsEvent(
			final AbstractBuild<?, ?> build,
			final BuildListener listener,
			final BitbucketBuildState state) {

		PrintStream logger = listener.getLogger();

		// exit if Jenkins root URL is not configured. Bitbucket build API
		// requires valid link to build in CI system.
		if (Jenkins.getInstance().getRootUrl() == null) {
			logger.println(
					"Cannot notify Bitbucket! (Jenkins Root URL not configured)");
			return true;
		}

		Collection<String> commitSha1s = lookupCommitSha1s(build, listener);
		for  (String commitSha1 : commitSha1s) {
			try {
				NotificationResult result
					= notifyBitbucket(logger, build, commitSha1, listener, state);
				if (result.indicatesSuccess) {
					logger.println(
						"Notified Bitbucket for commit with id "
								+ commitSha1);
				} else {
					logger.println(
					"Failed to notify Bitbucket for commit "
							+ commitSha1
							+ " (" + result.message + ")");
				}
            } catch (SSLPeerUnverifiedException e) {
	    		logger.println("SSLPeerUnverifiedException caught while "
    				+ "notifying Bitbucket. Make sure your SSL certificate on "
    				+ "your Bitbucket server is valid or check the "
    				+ " 'Ignore unverifiable SSL certificate' checkbox in the "
    				+ "Bitbucket plugin configuration of this job.");
			} catch (Exception e) {
				logger.println("Caught exception while notifying Bitbucket with id "
					+ commitSha1);
				e.printStackTrace(logger);
			}
		}
		if (commitSha1s.isEmpty()) {
			logger.println("found no commit info");
		}
		return true;
	}

	private Collection<String> lookupCommitSha1s(
			@SuppressWarnings("rawtypes") AbstractBuild build,
			BuildListener listener) {

		if (commitSha1 != null && commitSha1.trim().length() > 0) {
			PrintStream logger = listener.getLogger();
			try {
				return Arrays.asList(TokenMacro.expandAll(build, listener, commitSha1));
			} catch (IOException e) {
				logger.println("Unable to expand commit SHA value");
				e.printStackTrace(logger);
				return Arrays.asList();
			} catch (InterruptedException e) {
				logger.println("Unable to expand commit SHA value");
				e.printStackTrace(logger);
				return Arrays.asList();
			} catch (MacroEvaluationException e) {
				logger.println("Unable to expand commit SHA value");
				e.printStackTrace(logger);
				return Arrays.asList();
			}
		}

		// Use a set to remove duplicates
		Collection<String> sha1s = new HashSet<String>();
		// MultiSCM may add multiple BuildData actions for each SCM, but we are covered in any case
		for (BuildData buildData : build.getActions(BuildData.class)) {
			// get the sha1 of the commit that was built
			Revision lastBuiltRevision = buildData.getLastBuiltRevision();
			if (lastBuiltRevision == null) {
				continue;
			}
			String lastBuiltSha1 = lastBuiltRevision.getSha1String();

			// Should never be null, but may be blank
			if (!lastBuiltSha1.isEmpty()) {
				sha1s.add(lastBuiltSha1);
			}

			// This might be different than the lastBuiltSha1 if using "Merge before build"
			String markedSha1 = buildData.lastBuild.getMarked().getSha1String();

			// Should never be null, but may be blank
			if (!markedSha1.isEmpty()) {
				sha1s.add(markedSha1);
			}
		}
		return sha1s;
	}

	/**
	 * Returns the HttpClient through which the REST call is made. Uses an
	 * unsafe TrustStrategy in case the user specified a HTTPS URL and
	 * set the ignoreUnverifiedSSLPeer flag.
	 *
	 * @param logger    the logger to log messages to
	 * @param build
	 * @return			the HttpClient
	 */
	private HttpClient getHttpClient(PrintStream logger, AbstractBuild<?, ?> build) throws Exception {
        boolean ignoreUnverifiedSSL = ignoreUnverifiedSSLPeer;
        String bitbucketServer = bitbucketServerBaseUrl;
        DescriptorImpl descriptor = getDescriptor();

		// Determine if we are using the local or global settings
		String credentialsId = getCredentialsId();
		if (StringUtils.isBlank(credentialsId)) {
			credentialsId = descriptor.getCredentialsId();
		}

		Credentials credentials = CredentialsMatchers.firstOrNull(CredentialsProvider.lookupCredentials(CertificateCredentials.class,
				Jenkins.getInstance(), ACL.SYSTEM), CredentialsMatchers.withId(credentialsId));

        if ("".equals(bitbucketServer) || bitbucketServer == null) {
            bitbucketServer = descriptor.getBitbucketRootUrl();
        }
        if (!ignoreUnverifiedSSL) {
            ignoreUnverifiedSSL = descriptor.isIgnoreUnverifiedSsl();
        }

        URL url = new URL(bitbucketServer);
        HttpClientBuilder builder = HttpClientBuilder.create();
        if (url.getProtocol().equals("https")
                && (ignoreUnverifiedSSL || credentials instanceof CertificateCredentials)) {
			// add unsafe trust manager to avoid thrown
			// SSLPeerUnverifiedException
			try {
				SSLConnectionSocketFactory sslConnSocketFactory
						= new SSLConnectionSocketFactory(buildSslContext(ignoreUnverifiedSSL,credentials),
                        ignoreUnverifiedSSL ? SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER : null);
				builder.setSSLSocketFactory(sslConnSocketFactory);

				Registry<ConnectionSocketFactory> registry
						= RegistryBuilder.<ConnectionSocketFactory>create()
							.register("https", sslConnSocketFactory)
							.build();

				HttpClientConnectionManager ccm
						= new BasicHttpClientConnectionManager(registry);

				builder.setConnectionManager(ccm);
			} catch (NoSuchAlgorithmException nsae) {
				logger.println("Couldn't establish SSL context:");
				nsae.printStackTrace(logger);
			} catch (KeyManagementException kme) {
				logger.println("Couldn't initialize SSL context:");
				kme.printStackTrace(logger);
			} catch (KeyStoreException kse) {
				logger.println("Couldn't initialize SSL context:");
				kse.printStackTrace(logger);
			}
        }

        // Configure the proxy, if needed
        // Using the Jenkins methods handles the noProxyHost settings
        ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
        if (proxyConfig != null) {
            Proxy proxy = proxyConfig.createProxy(url.getHost());
            if (proxy != null && proxy.type() == Proxy.Type.HTTP) {
                SocketAddress addr = proxy.address();
                if (addr != null && addr instanceof InetSocketAddress) {
                    InetSocketAddress proxyAddr = (InetSocketAddress) addr;
                    HttpHost proxyHost = new HttpHost(proxyAddr.getAddress().getHostAddress(), proxyAddr.getPort());
                    builder = builder.setProxy(proxyHost);

                    String proxyUser = proxyConfig.getUserName();
                    if (proxyUser != null) {
                        String proxyPass = proxyConfig.getPassword();
                        BasicCredentialsProvider cred = new BasicCredentialsProvider();
                        cred.setCredentials(new AuthScope(proxyHost),
                                new UsernamePasswordCredentials(proxyUser, proxyPass));
                        builder = builder
                                .setDefaultCredentialsProvider(cred)
                                .setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy());
                    }
                }
            }
        }

        return builder.build();
    }

    /**
     * Helper in place to allow us to define out HttpClient SSL context
     *
     * @param ignoreUnverifiedSSL
     * @param credentials
     * @return
     * @throws UnrecoverableKeyException
     * @throws NoSuchAlgorithmException
     * @throws KeyStoreException
     * @throws KeyManagementException
     */
	private SSLContext buildSslContext(boolean ignoreUnverifiedSSL, Credentials credentials) throws UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {

		SSLContextBuilder customContext = SSLContexts.custom();
		if (credentials instanceof CertificateCredentials) {
			customContext  = customContext.loadKeyMaterial(((CertificateCredentials) credentials).getKeyStore(),((CertificateCredentials) credentials).getPassword().getPlainText().toCharArray());
		}
		if (ignoreUnverifiedSSL) {
			TrustStrategy easyStrategy = new TrustStrategy() {
				public boolean isTrusted(X509Certificate[] chain, String authType)
						throws CertificateException {
					return true;
				}
			};
			customContext = customContext
					.loadTrustMaterial(null, easyStrategy);
		}
		return customContext.useTLS().build();
}

	/**
     * Hudson defines a method {@link Builder#getDescriptor()}, which
     * returns the corresponding {@link Descriptor} object.
     *
     * Since we know that it's actually {@link DescriptorImpl}, override
     * the method and give a better return type, so that we can access
     * {@link DescriptorImpl} methods more easily.
     *
     * This is not necessary, but just a coding style preference.
     */
    @Override
    public DescriptorImpl getDescriptor() {
        // see Descriptor javadoc for more about what a descriptor is.
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
	public static final class DescriptorImpl
		extends BuildStepDescriptor<Publisher> {

        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        private String credentialsId;
        private String bitbucketRootUrl;
        private boolean ignoreUnverifiedSsl;
        private boolean includeBuildNumberInKey;
		private String projectKey;
		private boolean prependParentProjectKey;
		private boolean disableInprogressNotification;

		public DescriptorImpl() {
            load();
        }

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance()).hasPermission(Computer.CONFIGURE)) {
				return new ListBoxModel();
			}

			return new StandardListBoxModel().withEmptySelection().withMatching(new BitbucketCredentialMatcher(),CredentialsProvider.lookupCredentials(StandardCredentials.class, context, null, new ArrayList<DomainRequirement>()));
		}

        public String getBitbucketRootUrl() {
        	if ((bitbucketRootUrl == null) || (bitbucketRootUrl.trim().equals(""))) {
        		return null;
        	} else {
	            return bitbucketRootUrl;
        	}
        }

		public boolean isDisableInprogressNotification() {
			return disableInprogressNotification;
		}

		public String getCredentialsId() {
			return credentialsId;
		}

		public boolean isIgnoreUnverifiedSsl() {
            return ignoreUnverifiedSsl;
        }

        public boolean isIncludeBuildNumberInKey() {
            return includeBuildNumberInKey;
        }

		public String getProjectKey() {
			return projectKey;
		}

		public boolean isPrependParentProjectKey() {
			return prependParentProjectKey;
		}

		public FormValidation doCheckCredentialsId(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.trim().equals("")) {
				return FormValidation.error(
						"Please specify the credentials to use");
			} else {
				return FormValidation.ok();
			}
		}


		public FormValidation doCheckBitbucketServerBaseUrl(
					@QueryParameter String value)
				throws IOException, ServletException {

			// calculate effective url from global and local config
			String url = value;
			if ((url != null) && (!url.trim().equals(""))) {
				url = url.trim();
			} else {
				url = bitbucketRootUrl != null ? bitbucketRootUrl.trim() : null;
			}

			if ((url == null) || url.equals("")) {
				return FormValidation.error(
						"Please specify a valid URL here or in the global "
						+ "configuration");
			} else {
				try {
					new URL(url);
					return FormValidation.ok();
				} catch (Exception e) {
					return FormValidation.error(
						"Please specify a valid URL here or in the global "
						+ "configuration!");
				}
			}
		}

		@SuppressWarnings("rawtypes")
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		public String getDisplayName() {
			return "Notify Bitbucket Instance";
		}

		@Override
		public boolean configure(
				StaplerRequest req,
				JSONObject formData) throws FormException {

            // to persist global configuration information,
            // set that to properties and call save().
            bitbucketRootUrl = formData.getString("bitbucketRootUrl");
            ignoreUnverifiedSsl = formData.getBoolean("ignoreUnverifiedSsl");
            includeBuildNumberInKey = formData.getBoolean("includeBuildNumberInKey");

            if (formData.has("credentialsId") && StringUtils.isNotBlank(formData.getString("credentialsId"))) {
                credentialsId = formData.getString("credentialsId");
            }
            if (formData.has("projectKey")) {
                projectKey = formData.getString("projectKey");
            }
            prependParentProjectKey = formData.getBoolean("prependParentProjectKey");

			disableInprogressNotification = formData.getBoolean("disableInprogressNotification");

			save();
			return super.configure(req,formData);
		}
	}

	// non-public members ------------------------------------------------------

	/**
	 * Notifies the configured Bitbucket server by POSTing the build results
	 * to the Bitbucket build API.
	 *
	 * @param logger		the logger to use
	 * @param build			the build to notify Bitbucket of
	 * @param commitSha1	the SHA1 of the built commit
	 * @param listener		the build listener for logging
	 * @param state			the state of the build as defined by the Bitbucket API.
	 */
	private NotificationResult notifyBitbucket(
			final PrintStream logger,
			final AbstractBuild<?, ?> build,
			final String commitSha1,
			final BuildListener listener,
			final BitbucketBuildState state) throws Exception {
		HttpEntity bitbucketBuildNotificationEntity
			= newBitbucketBuildNotificationEntity(build, state, listener);
		HttpPost req = createRequest(bitbucketBuildNotificationEntity, commitSha1);
		HttpClient client = getHttpClient(logger,build);
		try {
			HttpResponse res = client.execute(req);
			if (res.getStatusLine().getStatusCode() != 200 &&
                    res.getStatusLine().getStatusCode() != 201) {
				return NotificationResult.newFailure(
						EntityUtils.toString(res.getEntity()));
			} else {
				return NotificationResult.newSuccess();
			}
		} finally {
			client.getConnectionManager().shutdown();
		}
	}

	/**
	 * Returns the HTTP POST request ready to be sent to the Bitbucket build API for
	 * the given build and change set.
	 *
	 * @param bitbucketBuildNotificationEntity	a entity containing the parameters
	 * 										for Bitbucket
	 * @param commitSha1	the SHA1 of the commit that was built
	 * @return				the HTTP POST request to the Bitbucket build API
	 */
	private HttpPost createRequest(
			final HttpEntity bitbucketBuildNotificationEntity,
			final String commitSha1) {

		String url = bitbucketServerBaseUrl;
        DescriptorImpl descriptor = getDescriptor();

        if ("".equals(url) || url == null)
            url = descriptor.getBitbucketRootUrl();

    // https://api.bitbucket.org/2.0/repositories/{owner}/{repo_slug}/commit/{revision}/statuses/build
		HttpPost req = new HttpPost(
				url
				+ "/commit/"
				+ commitSha1
        + "/statuses/build");

		// If we have a credential defined then we need to determine if it
		// is a basic auth

		credentialsId = getCredentialsId();
		if (StringUtils.isBlank(credentialsId)) {
			credentialsId = descriptor.getCredentialsId();
		}

		if (StringUtils.isNotBlank(credentialsId)) {

			Credentials credentials = CredentialsMatchers.firstOrNull(CredentialsProvider.lookupCredentials(com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials.class,
					Jenkins.getInstance(), ACL.SYSTEM), CredentialsMatchers.withId(credentialsId));
			if (credentials instanceof com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials) {
				req.addHeader(BasicScheme.authenticate(
						new UsernamePasswordCredentials(
								((com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials) credentials).getUsername(),
								((com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials) credentials).getPassword().getPlainText()),
						"UTF-8",
						false));
			}
		}

		req.addHeader("Content-type", "application/json");
		req.setEntity(bitbucketBuildNotificationEntity);

		return req;
	}

	/**
	 * Returns the HTTP POST entity body with the JSON representation of the
	 * builds result to be sent to the Bitbucket build API.
	 *
	 * @param build			the build to notify Bitbucket of
	 * @return				HTTP entity body for POST to Bitbucket build API
	 */
	private HttpEntity newBitbucketBuildNotificationEntity(
			final AbstractBuild<?, ?> build,
			final BitbucketBuildState state,
            BuildListener listener) throws UnsupportedEncodingException {

		JSONObject json = new JSONObject();

        json.put("state", state.name());

        json.put("key", abbreviate(getBuildKey(build, listener), MAX_FIELD_LENGTH));

        // This is to replace the odd character Jenkins injects to separate
        // nested jobs, especially when using the Cloudbees Folders plugin.
        // These characters cause Bitbucket to throw up.
        String fullName = StringEscapeUtils.
                escapeJavaScript(build.getFullDisplayName()).
                replaceAll("\\\\u00BB", "\\/");
        json.put("name", abbreviate(fullName, MAX_FIELD_LENGTH));

		json.put("description", abbreviate(getBuildDescription(build, state), MAX_FIELD_LENGTH));
		json.put("url", abbreviate(Jenkins.getInstance()
                .getRootUrl().concat(build.getUrl()), MAX_URL_FIELD_LENGTH));

        return new StringEntity(json.toString(), "UTF-8");
	}

	private static String abbreviate(String text, int maxWidth) {
		if (text == null) {
			return null;
		}
		if (maxWidth < 4) {
			throw new IllegalArgumentException("Minimum abbreviation width is 4");
		}
		if (text.length() <= maxWidth) {
			return text;
		}
		return text.substring(0, maxWidth - 3) + "...";
	}

	/**
	 * Return the old-fashion build key
	 *
	 * @param  build the build to notify Bitbucket of
	 * @return default build key
	 */
	private String getDefaultBuildKey(final AbstractBuild<?, ?> build) {
		StringBuilder key = new StringBuilder();

		key.append(build.getProject().getName());
		if (includeBuildNumberInKey
				|| getDescriptor().isIncludeBuildNumberInKey()) {
			key.append('-').append(build.getNumber());
		}
		key.append('-').append(Jenkins.getInstance().getRootUrl());

		return key.toString();
	}

	/**
	 * Returns the build key used in the Bitbucket notification. Includes the
	 * build number depending on the user setting.
	 *
	 * @param 	build	the build to notify Bitbucket of
	 * @return	the build key for the Bitbucket notification
	 */
	private String getBuildKey(final AbstractBuild<?, ?> build,
							   BuildListener listener) {

		StringBuilder key = new StringBuilder();

		if (prependParentProjectKey || getDescriptor().isPrependParentProjectKey()){
			if (null != build.getParent().getParent()) {
				key.append(build.getParent().getParent().getFullName()).append('-');
			}
		}

		String overriddenKey = (projectKey != null && projectKey.trim().length() > 0) ? projectKey : getDescriptor().getProjectKey();

		if (overriddenKey != null && overriddenKey.trim().length() > 0) {
			PrintStream logger = listener.getLogger();
			try {
				key.append(TokenMacro.expandAll(build, listener, projectKey));
			} catch (IOException e) {
				logger.println("Cannot expand build key from parameter. Processing with default build key");
				e.printStackTrace(logger);
				key.append(getDefaultBuildKey(build));
			} catch (InterruptedException e) {
				logger.println("Cannot expand build key from parameter. Processing with default build key");
				e.printStackTrace(logger);
				key.append(getDefaultBuildKey(build));
			} catch (MacroEvaluationException e) {
				logger.println("Cannot expand build key from parameter. Processing with default build key");
				e.printStackTrace(logger);
				key.append(getDefaultBuildKey(build));
			}
		} else {
			key.append(getDefaultBuildKey(build));
		}

		return StringEscapeUtils.escapeJavaScript(key.toString());
	}

	/**
	 * Returns the description of the build used for the Bitbucket notification.
	 * Uses the build description provided by the Jenkins job, if available.
	 *
	 * @param build		the build to be described
	 * @param state		the state of the build
	 * @return			the description of the build
	 */
	private String getBuildDescription(
			final AbstractBuild<?, ?> build,
			final BitbucketBuildState state) {

		if (build.getDescription() != null
				&& build.getDescription().trim().length() > 0) {

			return build.getDescription();
		} else {
			switch (state) {
			case INPROGRESS:
	            return "building on Jenkins @ "
					+ Jenkins.getInstance().getRootUrl();
			default:
	            return "built by Jenkins @ "
	            	+ Jenkins.getInstance().getRootUrl();
			}
		}
	}
}
