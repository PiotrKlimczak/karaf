/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.tooling;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import aQute.bnd.osgi.Macro;
import aQute.bnd.osgi.Processor;
import org.apache.felix.resolver.Logger;
import org.apache.felix.resolver.ResolverImpl;
import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.apache.karaf.features.FeatureEvent;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.internal.download.DownloadCallback;
import org.apache.karaf.features.internal.download.DownloadManager;
import org.apache.karaf.features.internal.download.Downloader;
import org.apache.karaf.features.internal.download.StreamProvider;
import org.apache.karaf.features.internal.model.Conditional;
import org.apache.karaf.features.internal.model.ConfigFile;
import org.apache.karaf.features.internal.model.Feature;
import org.apache.karaf.features.internal.model.Features;
import org.apache.karaf.features.internal.model.JaxbUtil;
import org.apache.karaf.features.internal.resolver.ResourceBuilder;
import org.apache.karaf.features.internal.resolver.ResourceImpl;
import org.apache.karaf.features.internal.resolver.ResourceUtils;
import org.apache.karaf.features.internal.service.Deployer;
import org.apache.karaf.features.internal.service.State;
import org.apache.karaf.features.internal.util.MapUtils;
import org.apache.karaf.features.internal.util.MultiException;
import org.apache.karaf.profile.assembly.CustomDownloadManager;
import org.apache.karaf.tooling.utils.MojoSupport;
import org.apache.karaf.util.config.PropertiesLoader;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.ops4j.pax.url.mvn.MavenResolver;
import org.ops4j.pax.url.mvn.MavenResolvers;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;

import static java.util.jar.JarFile.MANIFEST_NAME;

@Mojo(name = "verify", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class VerifyMojo extends MojoSupport {

    @Parameter(property = "descriptors")
    protected Set<String> descriptors;

    @Parameter(property = "features")
    protected Set<String> features;

    @Parameter(property = "framework")
    protected Set<String> framework;

    @Parameter(property = "configuration")
    protected String configuration;

    @Parameter(property = "distribution", defaultValue = "org.apache.karaf:apache-karaf")
    protected String distribution;

    @Parameter(property = "javase")
    protected String javase;

    @Parameter(property = "dist-dir")
    protected String distDir;

    @Parameter(property = "karaf-version")
    protected String karafVersion;

    @Parameter(property = "additional-metadata")
    protected File additionalMetadata;

    @Parameter(property = "ignore-missing-conditions")
    protected boolean ignoreMissingConditions;

    @Parameter(property = "fail")
    protected String fail = "end";

    @Parameter(property = "verify-transitive")
    protected boolean verifyTransitive = false;

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    @Parameter(property = "skip", defaultValue = "${features.verify.skip}")
    protected boolean skip;

    protected MavenResolver resolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            return;
        }

        if (karafVersion == null) {
            Properties versions = new Properties();
            try (InputStream is = getClass().getResourceAsStream("versions.properties")) {
                versions.load(is);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            karafVersion = versions.getProperty("karaf-version");
        }

        Hashtable<String, String> config = new Hashtable<>();
        StringBuilder remote = new StringBuilder();
        for (Object obj : project.getRemoteProjectRepositories()) {
            if (remote.length() > 0) {
                remote.append(",");
            }
            remote.append(invoke(obj, "getUrl"));
            remote.append("@id=").append(invoke(obj, "getId"));
            if (!((Boolean) invoke(getPolicy(obj, false), "isEnabled"))) {
                remote.append("@noreleases");
            }
            if ((Boolean) invoke(getPolicy(obj, true), "isEnabled")) {
                remote.append("@snapshots");
            }
        }
        getLog().info("Using repositories: " + remote.toString());
        config.put("maven.repositories", remote.toString());
        config.put("maven.localRepository", localRepo.getBasedir());
        config.put("maven.settings", mavenSession.getRequest().getUserSettingsFile().toString());
        // TODO: add more configuration bits ?
        resolver = MavenResolvers.createMavenResolver(config, "maven");
        doExecute();
    }

    private String getVersion(String id, String def) {
        String v = getVersion(id);
        return v != null ? v : def;
    }

    private String getVersion(String id) {
        Artifact artifact = project.getArtifactMap().get(id);
        if (artifact != null) {
            return artifact.getBaseVersion();
        } else if (id.startsWith("org.apache.karaf")) {
            return karafVersion;
        } else {
            return null;
        }
    }

    private Object invoke(Object object, String getter) throws MojoExecutionException {
        try {
            return object.getClass().getMethod(getter).invoke(object);
        } catch (Exception e) {
            throw new MojoExecutionException("Unable to build remote repository from " + object.toString(), e);
        }
    }

    private Object getPolicy(Object object, boolean snapshots) throws MojoExecutionException {
        return invoke(object, "getPolicy", new Class[] { Boolean.TYPE }, new Object[] { snapshots });
    }

    private Object invoke(Object object, String getter, Class[] types, Object[] params) throws MojoExecutionException {
        try {
            return object.getClass().getMethod(getter, types).invoke(object, params);
        } catch (Exception e) {
            throw new MojoExecutionException("Unable to build remote repository from " + object.toString(), e);
        }
    }

    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        System.setProperty("karaf.home", "target/karaf");
        System.setProperty("karaf.data", "target/karaf/data");

        Hashtable<String, String> properties = new Hashtable<>();

        if (additionalMetadata != null) {
            try (Reader reader = new FileReader(additionalMetadata)) {
                Properties metadata = new Properties();
                metadata.load(reader);
                for (Enumeration<?> e = metadata.propertyNames(); e.hasMoreElements(); ) {
                    Object key = e.nextElement();
                    Object val = metadata.get(key);
                    properties.put(key.toString(), val.toString());
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to load additional metadata from " + additionalMetadata, e);
            }
        }

        Set<String> allDescriptors = new LinkedHashSet<>();
        if (descriptors == null) {
            if (framework == null) {
                framework = Collections.singleton("framework");
            }
            descriptors = new LinkedHashSet<>();
            if (framework.contains("framework")) {
                allDescriptors.add("mvn:org.apache.karaf.features/framework/" + getVersion("org.apache.karaf.features:framework") + "/xml/features");
            }
            allDescriptors.add("file:" + project.getBuild().getDirectory() + "/feature/feature.xml");
        } else {
            allDescriptors.addAll(descriptors);
            if (framework != null && framework.contains("framework")) {
                allDescriptors.add("mvn:org.apache.karaf.features/framework/" + getVersion("org.apache.karaf.features:framework") + "/xml/features");
            }
        }

        // TODO: allow using external configuration ?
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(8);
        DownloadManager manager = new CustomDownloadManager(resolver, executor);
        final Map<String, Features> repositories;
        Map<String, List<Feature>> allFeatures = new HashMap<>();
        try {
            repositories = loadRepositories(manager, allDescriptors);
            for (String repoUri : repositories.keySet()) {
                List<Feature> features = repositories.get(repoUri).getFeature();
                // Ack features to inline configuration files urls
                for (Feature feature : features) {
                    for (org.apache.karaf.features.internal.model.Bundle bi : feature.getBundle()) {
                        String loc = bi.getLocation();
                        String nloc = null;
                        if (loc.contains("file:")) {
                            for (ConfigFile cfi : feature.getConfigfile()) {
                                if (cfi.getFinalname().substring(1)
                                        .equals(loc.substring(loc.indexOf("file:") + "file:".length()))) {
                                    nloc = cfi.getLocation();
                                }
                            }
                        }
                        if (nloc != null) {
                            Field field = bi.getClass().getDeclaredField("location");
                            field.setAccessible(true);
                            field.set(bi, loc.substring(0, loc.indexOf("file:")) + nloc);
                        }
                    }
                }
                allFeatures.put(repoUri, features);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Unable to load features descriptors", e);
        }

        List<Feature> featuresToTest = new ArrayList<>();
        if (verifyTransitive) {
            for (List<Feature> features : allFeatures.values()) {
                featuresToTest.addAll(features);
            }
        } else {
            for (String uri : descriptors) {
                featuresToTest.addAll(allFeatures.get(uri));
            }
        }
        if (features != null && !features.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String feature : features) {
                if (sb.length() > 0) {
                    sb.append("|");
                }
                String p = feature.replaceAll("\\.", "\\\\.").replaceAll("\\*", ".*");
                sb.append(p);
                if (!feature.contains("/")) {
                    sb.append("/.*");
                }
            }
            Pattern pattern = Pattern.compile(sb.toString());
            for (Iterator<Feature> iterator = featuresToTest.iterator(); iterator.hasNext();) {
                Feature feature = iterator.next();
                String id = feature.getName() + "/" + feature.getVersion();
                if (!pattern.matcher(id).matches()) {
                    iterator.remove();
                }
            }
        }

        for (String fmk : framework) {
            properties.put("feature.framework." + fmk, fmk);
        }
        List<Exception> failures = new ArrayList<>();
        for (Feature feature : featuresToTest) {
            try {
                String id = feature.getName() + "/" + feature.getVersion();
                verifyResolution(new CustomDownloadManager(resolver, executor),
                                 repositories, Collections.singleton(id), properties);
                getLog().info("Verification of feature " + id + " succeeded");
            } catch (Exception e) {
                if (e.getCause() instanceof ResolutionException) {
                    getLog().warn(e.getMessage());
                } else {
                    getLog().warn(e);
                }
                failures.add(e);
                if ("first".equals(fail)) {
                    throw e;
                }
            }
            for (Conditional cond : feature.getConditional()) {
                Set<String> ids = new LinkedHashSet<>();
                ids.add(feature.getId());
                ids.addAll(cond.getCondition());
                try {
                    verifyResolution(manager, repositories, ids, properties);
                    getLog().info("Verification of feature " + ids + " succeeded");
                } catch (Exception e) {
                    if (ignoreMissingConditions && e.getCause() instanceof ResolutionException) {
                        boolean ignore = true;
                        Collection<Requirement> requirements = ((ResolutionException) e.getCause()).getUnresolvedRequirements();
                        for (Requirement req : requirements) {
                            ignore &= (IdentityNamespace.IDENTITY_NAMESPACE.equals(req.getNamespace())
                                    && ResourceUtils.TYPE_FEATURE.equals(req.getAttributes().get("type"))
                                    && cond.getCondition().contains(req.getAttributes().get(IdentityNamespace.IDENTITY_NAMESPACE).toString()));
                        }
                        if (ignore) {
                            getLog().warn("Feature resolution failed for " + ids
                                    + "\nMessage: " + e.getCause().getMessage());
                            continue;
                        }
                    }
                    if (e.getCause() instanceof ResolutionException) {
                        getLog().warn(e.getMessage());
                    } else {
                        getLog().warn(e);
                    }
                    failures.add(e);
                    if ("first".equals(fail)) {
                        throw e;
                    }
                }
            }
        }
        if ("end".equals(fail) && !failures.isEmpty()) {
            throw new MojoExecutionException("Verification failures", new MultiException("Verification failures", failures));
        }
    }

    private void verifyResolution(DownloadManager manager, final Map<String, Features> repositories, Set<String> features, Hashtable<String, String> properties) throws MojoExecutionException {
        try {
            Bundle systemBundle = getSystemBundle(getMetadata(properties, "metadata#"));
            DummyDeployCallback callback = new DummyDeployCallback(systemBundle, repositories.values());
            Deployer deployer = new Deployer(manager, new ResolverImpl(new MavenResolverLog()), callback);


            // Install framework
            Deployer.DeploymentRequest request = createDeploymentRequest();

            for (String fmwk : framework) {
                MapUtils.addToMapSet(request.requirements, FeaturesService.ROOT_REGION, fmwk);
            }
            try {
                deployer.deploy(callback.getDeploymentState(), request);
            } catch (Exception e) {
                throw new MojoExecutionException("Unable to resolve framework features", e);
            }


            /*
            boolean resolveOptionalImports = getResolveOptionalImports(properties);

            DeploymentBuilder builder = new DeploymentBuilder(
                    manager,
                    null,
                    repositories.values(),
                    -1 // Disable url handlers
            );
            Map<String, Resource> downloadedResources = builder.download(
                    getPrefixedProperties(properties, "feature."),
                    getPrefixedProperties(properties, "bundle."),
                    getPrefixedProperties(properties, "fab."),
                    getPrefixedProperties(properties, "req."),
                    getPrefixedProperties(properties, "override."),
                    getPrefixedProperties(properties, "optional."),
                    getMetadata(properties, "metadata#")
            );

            for (String uri : getPrefixedProperties(properties, "resources.")) {
                builder.addResourceRepository(new MetadataRepository(new HttpMetadataProvider(uri)));
            }
            */


            // Install features
            for (String feature : features) {
                MapUtils.addToMapSet(request.requirements, FeaturesService.ROOT_REGION, feature);
            }
            try {
                Set<String> prereqs = new HashSet<>();
                while (true) {
                    try {
                        deployer.deploy(callback.getDeploymentState(), request);
                        break;
                    } catch (Deployer.PartialDeploymentException e) {
                        if (!prereqs.containsAll(e.getMissing())) {
                            prereqs.addAll(e.getMissing());
                        } else {
                            throw new Exception("Deployment aborted due to loop in missing prerequisites: " + e.getMissing());
                        }
                    }
                }
                // TODO: find unused resources ?
            } catch (Exception e) {
                throw new MojoExecutionException("Feature resolution failed for " + features
                        + "\nMessage: " + e.getMessage()
                        + "\nRepositories: " + toString(new TreeSet<>(repositories.keySet()))
                        + "\nResources: " + toString(new TreeSet<>(manager.getProviders().keySet())), e);
            }


        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Error verifying feature " + features + "\nMessage: " + e.getMessage(), e);
        }
    }

    private Deployer.DeploymentRequest createDeploymentRequest() {
        Deployer.DeploymentRequest request = new Deployer.DeploymentRequest();
        request.bundleUpdateRange = FeaturesService.DEFAULT_BUNDLE_UPDATE_RANGE;
        request.featureResolutionRange = FeaturesService.DEFAULT_FEATURE_RESOLUTION_RANGE;
        request.serviceRequirements = FeaturesService.SERVICE_REQUIREMENTS_DEFAULT;
        request.overrides = new HashSet<>();
        request.requirements = new HashMap<>();
        request.stateChanges = new HashMap<>();
        request.options = EnumSet.noneOf(FeaturesService.Option.class);
        return request;
    }

    private String toString(Collection<String> collection) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        for (String s : collection) {
            sb.append("\t").append(s).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private Bundle getSystemBundle(Map<String, Map<VersionRange, Map<String, String>>> metadata) throws Exception {
        URL configPropURL;
        if (configuration != null) {
            configPropURL = new URL(configuration);
        } else {
            Artifact karafDistro = project.getArtifactMap().get(distribution);
            if (karafDistro != null) {
                String dir = distDir;
                if ("kar".equals(karafDistro.getType()) && dir == null) {
                    dir = "resources";
                }
                if (dir == null) {
                    dir = karafDistro.getArtifactId() + "-" + karafDistro.getBaseVersion();
                }
                configPropURL = new URL("jar:file:" + karafDistro.getFile() + "!/" + dir + "/etc/config.properties");
            } else {
                String version = getVersion(distribution, "RELEASE");
                String[] dist = distribution.split(":");
                File distFile = resolver.resolve(dist[0], dist[1], null, "zip", version);
                String resolvedVersion = distFile.getName().substring(dist[1].length() + 1, distFile.getName().length() - 4);
                String dir = distDir;
                if (dir == null) {
                    dir = dist[1] + "-" + resolvedVersion;
                }
                configPropURL = new URL("jar:file:" + distFile + "!/" + dir + "/etc/config.properties");
            }
        }
        org.apache.felix.utils.properties.Properties configProps = PropertiesLoader.loadPropertiesFile(configPropURL, true);
//        copySystemProperties(configProps);
        if (javase == null) {
            configProps.put("java.specification.version", System.getProperty("java.specification.version"));
        } else {
            configProps.put("java.specification.version", javase);
        }
        configProps.substitute();

        Attributes attributes = new Attributes();
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "system.bundle");
        attributes.putValue(Constants.BUNDLE_VERSION, "0.0.0");

        String exportPackages = configProps.getProperty("org.osgi.framework.system.packages");
        if (configProps.containsKey("org.osgi.framework.system.packages.extra")) {
            exportPackages += "," + configProps.getProperty("org.osgi.framework.system.packages.extra");
        }
        exportPackages = exportPackages.replaceAll(",\\s*,", ",");
        attributes.putValue(Constants.EXPORT_PACKAGE, exportPackages);

        String systemCaps = configProps.getProperty("org.osgi.framework.system.capabilities");
        attributes.putValue(Constants.PROVIDE_CAPABILITY, systemCaps);

        // TODO: support metadata overrides on system bundle
//        attributes = DeploymentBuilder.overrideAttributes(attributes, metadata);

        final Hashtable<String, String> headers = new Hashtable<>();
        for (Map.Entry attr : attributes.entrySet()) {
            headers.put(attr.getKey().toString(), attr.getValue().toString());
        }

        final FakeBundleRevision resource = new FakeBundleRevision(headers, "system-bundle", 0l);
        return resource.getBundle();
    }


    public static Map<String, Features> loadRepositories(DownloadManager manager, Set<String> uris) throws Exception {
        final Map<String, Features> loaded = new HashMap<>();
        final Downloader downloader = manager.createDownloader();
        for (String repository : uris) {
            downloader.download(repository, new DownloadCallback() {
                @Override
                public void downloaded(final StreamProvider provider) throws Exception {
                    try (InputStream is = provider.open()) {
                        Features featuresModel = JaxbUtil.unmarshal(provider.getUrl(), is, false);
                        synchronized (loaded) {
                            loaded.put(provider.getUrl(), featuresModel);
                            for (String innerRepository : featuresModel.getRepository()) {
                                downloader.download(innerRepository, this);
                            }
                        }
                    }
                }
            });
        }
        downloader.await();
        return loaded;
    }

    public static Set<String> getPrefixedProperties(Map<String, String> properties, String prefix) {
        Set<String> result = new HashSet<>();
        for (String key : properties.keySet()) {
            if (key.startsWith(prefix)) {
                String url = properties.get(key);
                if (url == null || url.length() == 0) {
                    url = key.substring(prefix.length());
                }
                if (!url.isEmpty()) {
                    result.add(url);
                }
            }
        }
        return result;
    }

    public static Map<String, Map<VersionRange, Map<String, String>>> getMetadata(Map<String, String> properties, String prefix) {
        Map<String, Map<VersionRange, Map<String, String>>> result = new HashMap<>();
        for (String key : properties.keySet()) {
            if (key.startsWith(prefix)) {
                String val = properties.get(key);
                key = key.substring(prefix.length());
                String[] parts = key.split("#");
                if (parts.length == 3) {
                    Map<VersionRange, Map<String, String>> ranges = result.get(parts[0]);
                    if (ranges == null) {
                        ranges = new HashMap<>();
                        result.put(parts[0], ranges);
                    }
                    String version = parts[1];
                    if (!version.startsWith("[") && !version.startsWith("(")) {
                        Processor processor = new Processor();
                        processor.setProperty("@", VersionTable.getVersion(version).toString());
                        Macro macro = new Macro(processor);
                        version = macro.process("${range;[==,=+)}");
                    }
                    VersionRange range = new VersionRange(version);
                    Map<String, String> hdrs = ranges.get(range);
                    if (hdrs == null) {
                        hdrs = new HashMap<>();
                        ranges.put(range, hdrs);
                    }
                    hdrs.put(parts[2], val);
                }
            }
        }
        return result;
    }

    public static class FakeBundleRevision extends ResourceImpl implements BundleRevision, BundleStartLevel {

        private final Bundle bundle;
        private int startLevel;

        public FakeBundleRevision(final Hashtable<String, String> headers, final String location, final long bundleId) throws BundleException {
            ResourceBuilder.build(this, location, headers);
            this.bundle = (Bundle) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[] { Bundle.class },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getName().equals("hashCode")) {
                                return FakeBundleRevision.this.hashCode();
                            } else if (method.getName().equals("equals")) {
                                return proxy == args[0];
                            } else if (method.getName().equals("toString")) {
                                return bundle.getSymbolicName() + "/" + bundle.getVersion();
                            } else if (method.getName().equals("adapt")) {
                                if (args.length == 1 && args[0] == BundleRevision.class) {
                                    return FakeBundleRevision.this;
                                } else if (args.length == 1 && args[0] == BundleStartLevel.class) {
                                    return FakeBundleRevision.this;
                                }
                            } else if (method.getName().equals("getHeaders")) {
                                return headers;
                            } else if (method.getName().equals("getBundleId")) {
                                return bundleId;
                            } else if (method.getName().equals("getLocation")) {
                                return location;
                            } else if (method.getName().equals("getSymbolicName")) {
                                String name = headers.get(Constants.BUNDLE_SYMBOLICNAME);
                                int idx = name.indexOf(';');
                                if (idx > 0) {
                                    name = name.substring(0, idx).trim();
                                }
                                return name;
                            } else if (method.getName().equals("getVersion")) {
                                return new Version(headers.get(Constants.BUNDLE_VERSION));
                            } else if (method.getName().equals("getState")) {
                                return Bundle.ACTIVE;
                            } else if (method.getName().equals("getLastModified")) {
                                return 0l;
                            }
                            return null;
                        }
                    });
        }

        @Override
        public int getStartLevel() {
            return startLevel;
        }

        @Override
        public void setStartLevel(int startLevel) {
            this.startLevel = startLevel;
        }

        @Override
        public boolean isPersistentlyStarted() {
            return true;
        }

        @Override
        public boolean isActivationPolicyUsed() {
            return false;
        }

        @Override
        public String getSymbolicName() {
            return bundle.getSymbolicName();
        }

        @Override
        public Version getVersion() {
            return bundle.getVersion();
        }

        @Override
        public List<BundleCapability> getDeclaredCapabilities(String namespace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BundleRequirement> getDeclaredRequirements(String namespace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getTypes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BundleWiring getWiring() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bundle getBundle() {
            return bundle;
        }
    }

    public static class DummyDeployCallback implements Deployer.DeployCallback {

        private final Bundle systemBundle;
        private final Deployer.DeploymentState dstate;
        private final AtomicLong nextBundleId = new AtomicLong(0);

        public DummyDeployCallback(Bundle sysBundle, Collection<Features> repositories) throws Exception {
            systemBundle = sysBundle;
            dstate = new Deployer.DeploymentState();
            dstate.bundles = new HashMap<>();
            dstate.features = new HashMap<>();
            dstate.bundlesPerRegion = new HashMap<>();
            dstate.filtersPerRegion = new HashMap<>();
            dstate.state = new State();

            MapUtils.addToMapSet(dstate.bundlesPerRegion, FeaturesService.ROOT_REGION, 0l);
            dstate.bundles.put(0l, systemBundle);
            for (Features repo : repositories) {
                for (Feature f : repo.getFeature()) {
                    dstate.features.put(f.getId(), f);
                }
            }
        }

        public Deployer.DeploymentState getDeploymentState() {
            return dstate;
        }

        @Override
        public void print(String message, boolean verbose) {
        }

        @Override
        public void saveState(State state) {
            dstate.state.replace(state);
        }

        @Override
        public void persistResolveRequest(Deployer.DeploymentRequest request) throws IOException {
        }

        @Override
        public void installFeature(org.apache.karaf.features.Feature feature) throws IOException, InvalidSyntaxException {
        }

        @Override
        public void callListeners(FeatureEvent featureEvent) {
        }

        @Override
        public Bundle installBundle(String region, String uri, InputStream is) throws BundleException {
            try {
                Hashtable<String, String> headers = new Hashtable<>();
                ZipInputStream zis = new ZipInputStream(is);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (MANIFEST_NAME.equals(entry.getName())) {
                        Attributes attributes = new Manifest(zis).getMainAttributes();
                        for (Map.Entry attr : attributes.entrySet()) {
                            headers.put(attr.getKey().toString(), attr.getValue().toString());
                        }
                    }
                }
                BundleRevision revision = new FakeBundleRevision(headers, uri, nextBundleId.incrementAndGet());
                Bundle bundle = revision.getBundle();
                MapUtils.addToMapSet(dstate.bundlesPerRegion, region, bundle.getBundleId());
                dstate.bundles.put(bundle.getBundleId(), bundle);
                return bundle;
            } catch (IOException e) {
                throw new BundleException("Unable to install bundle", e);
            }
        }

        @Override
        public void updateBundle(Bundle bundle, String uri, InputStream is) throws BundleException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void uninstall(Bundle bundle) throws BundleException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void startBundle(Bundle bundle) throws BundleException {
        }

        @Override
        public void stopBundle(Bundle bundle, int options) throws BundleException {
        }

        @Override
        public void setBundleStartLevel(Bundle bundle, int startLevel) {
        }

        @Override
        public void refreshPackages(Collection<Bundle> bundles) throws InterruptedException {
        }

        @Override
        public void resolveBundles(Set<Bundle> bundles, Map<Resource, List<Wire>> wiring, Map<Resource, Bundle> resToBnd) {
        }

        @Override
        public void replaceDigraph(Map<String, Map<String, Map<String, Set<String>>>> policies, Map<String, Set<Long>> bundles) throws BundleException, InvalidSyntaxException {
        }
    }

    public class MavenResolverLog extends org.apache.felix.resolver.Logger {

        public MavenResolverLog() {
            super(Logger.LOG_DEBUG);
        }

        @Override
        protected void doLog(int level, String msg, Throwable throwable) {
            switch (level) {
            case LOG_DEBUG:
                getLog().debug(msg, throwable);
                break;
            case LOG_INFO:
                getLog().info(msg, throwable);
                break;
            case LOG_WARNING:
                getLog().warn(msg, throwable);
                break;
            case LOG_ERROR:
                getLog().error(msg, throwable);
                break;
            }
        }
    }
}
