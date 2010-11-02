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
package org.apache.felix.scrplugin.mojo;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import org.apache.felix.scrplugin.*;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.*;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

/**
 * The <code>SCRDescriptorMojo</code>
 * generates a service descriptor file based on annotations found in the sources.
 *
 * @goal scr
 * @phase process-classes
 * @description Build Service Descriptors from Java Source
 * @requiresDependencyResolution compile
 */
public class SCRDescriptorMojo extends AbstractMojo {

    /**
     * The groupID of the SCR Annotation library
     *
     * @see #SCR_ANN_MIN_VERSION
     * @see #checkAnnotationArtifact(Artifact)
     */
    private static final String SCR_ANN_GROUPID = "org.apache.felix";

    /**
     * The artifactID of the SCR Annotation library.
     *
     * @see #SCR_ANN_MIN_VERSION
     * @see #checkAnnotationArtifact(Artifact)
     */
    private static final String SCR_ANN_ARTIFACTID = "org.apache.felix.scr.annotations";

    /**
     * The minimum SCR Annotation library version supported by this plugin. See
     * FELIX-2680 for full details.
     *
     * @see #checkAnnotationArtifact(Artifact)
     */
    private static final ArtifactVersion SCR_ANN_MIN_VERSION = new DefaultArtifactVersion("1.3.1-SNAPSHOT");

    /**
     * @parameter expression="${project.build.directory}/scr-plugin-generated"
     * @required
     * @readonly
     */
    private File outputDirectory;

    /**
     * The Maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * Name of the generated descriptor.
     *
     * @parameter expression="${scr.descriptor.name}" default-value="serviceComponents.xml"
     */
    private String finalName;

    /**
     * Name of the generated meta type file.
     *
     * @parameter default-value="metatype.xml"
     */
    private String metaTypeName;

    /**
     * This flag controls the generation of the bind/unbind methods.
     * @parameter default-value="true"
     */
    private boolean generateAccessors;

    /**
     * This flag controls whether the javadoc source code will be scanned for
     * tags.
     * @parameter default-value="true"
     */
    protected boolean parseJavadoc;

    /**
     * This flag controls whether the annotations in the sources will be
     * processed.
     * @parameter default-value="true"
     */
    protected boolean processAnnotations;

    /**
     * In strict mode the plugin even fails on warnings.
     * @parameter default-value="false"
     */
    protected boolean strictMode;

    /**
     * The comma separated list of tokens to exclude when processing sources.
     *
     * @parameter alias="excludes"
     */
    private String sourceExcludes;

    /**
     * Predefined properties.
     *
     * @parameter
     */
    private Map<String, String> properties = new HashMap<String, String>();

    /**
     * Allows to define additional implementations of the interface
     * {@link org.apache.felix.scrplugin.tags.annotation.AnnotationTagProvider}
     * that provide mappings from custom annotations to
     * {@link org.apache.felix.scrplugin.tags.JavaTag} implementations.
     * List of full qualified class file names.
     *
     * @parameter
     */
    private String[] annotationTagProviders = {};

    /**
     * The version of the DS spec this plugin generates a descriptor for.
     * By default the version is detected by the used tags.
     * @parameter
     */
    private String specVersion;


    public void execute() throws MojoExecutionException, MojoFailureException
    {
        try
        {
            final org.apache.felix.scrplugin.Log scrLog = new MavenLog( getLog() );

            final ClassLoader classLoader = new URLClassLoader( getClassPath(), this.getClass().getClassLoader() );
            final JavaClassDescriptorManager jManager = new MavenJavaClassDescriptorManager( project, scrLog,
                classLoader, this.annotationTagProviders, this.sourceExcludes, this.parseJavadoc,
                this.processAnnotations );

            final SCRDescriptorGenerator generator = new SCRDescriptorGenerator( scrLog );

            // setup from plugin configuration
            generator.setOutputDirectory( outputDirectory );
            generator.setDescriptorManager( jManager );
            generator.setFinalName( finalName );
            generator.setMetaTypeName( metaTypeName );
            generator.setGenerateAccessors( generateAccessors );
            generator.setStrictMode( strictMode );
            generator.setProperties( properties );
            generator.setSpecVersion( specVersion );

            if ( generator.execute() )
            {
                setServiceComponentHeader();
                addResources();
            }
        }
        catch ( SCRDescriptorException sde )
        {
            throw new MojoExecutionException( sde.getMessage(), sde.getCause() );
        }
        catch ( SCRDescriptorFailureException sdfe )
        {
            throw ( MojoFailureException ) new MojoFailureException( sdfe.getMessage() ).initCause( sdfe );
        }
    }

    private URL[] getClassPath() throws MojoFailureException{
        @SuppressWarnings("unchecked")
        List<Artifact> artifacts = this.project.getCompileArtifacts();
        ArrayList<URL> path = new ArrayList<URL>();

        try
        {
            path.add(new File( this.project.getBuild().getOutputDirectory() ).toURI().toURL());
        }
        catch ( IOException ioe )
        {
            throw new MojoFailureException( "Unable to add target directory to classloader.");
        }

        for (Iterator<Artifact> ai=artifacts.iterator(); ai.hasNext(); ) {
            Artifact a = ai.next();
            assertMinScrAnnotationArtifactVersion(a);
            try {
                path.add(a.getFile().toURI().toURL());
            } catch (IOException ioe) {
                throw new MojoFailureException("Unable to get compile class loader.");
            }
        }

        return path.toArray( new URL[path.size()] );
    }

    /**
     * Asserts that the artifact is at least version
     * {@link #SCR_ANN_MIN_VERSION} if it is
     * org.apache.felix:org.apache.felix.scr.annotations. If the version is
     * lower then the build fails because as of Maven SCR Plugin 1.6.0 the old
     * SCR Annotation libraries do not produce descriptors any more. If the
     * artifact is not this method silently returns.
     *
     * @param a The artifact to check and assert
     * @see #SCR_ANN_ARTIFACTID
     * @see #SCR_ANN_GROUPID
     * @see #SCR_ANN_MIN_VERSION
     * @throws MojoFailureException If the artifact refers to the SCR Annotation
     *             library with a version less than {@link #SCR_ANN_MIN_VERSION}
     */
    private void assertMinScrAnnotationArtifactVersion(Artifact a) throws MojoFailureException
    {
        if (SCR_ANN_ARTIFACTID.equals(a.getArtifactId()) && SCR_ANN_GROUPID.equals(a.getGroupId()))
        {
            // compare version number
            try
            {
                ArtifactVersion aVersion = a.getSelectedVersion();
                if (SCR_ANN_MIN_VERSION.compareTo(aVersion) > 0)
                {
                    getLog().error("Project depends on " + a);
                    getLog().error("Minimum required version is " + SCR_ANN_MIN_VERSION);
                    throw new MojoFailureException(
                        "Please use org.apache.felix:org.apache.felix.scr.annotations version " + SCR_ANN_MIN_VERSION
                            + " or newer.");
                }
            }
            catch (OverConstrainedVersionException oe)
            {
                getLog().error(oe.toString());
                getLog().debug(oe);
                throw new MojoFailureException(oe.getMessage());
            }
        }
    }

    private void setServiceComponentHeader()
    {
        final File descriptorFile = StringUtils.isEmpty( this.finalName ) ? null : new File( new File(
            this.outputDirectory, "OSGI-INF" ), this.finalName );
        if ( descriptorFile.exists() )
        {
            String svcComp = project.getProperties().getProperty( "Service-Component" );
            final String svcPath = "OSGI-INF/" + finalName;
            svcComp = ( svcComp == null ) ? svcPath :
                svcComp.contains(svcPath) ? svcComp : svcComp + ", " + svcPath;
            project.getProperties().setProperty( "Service-Component", svcComp );
        }
    }


    private void addResources()
    {
        // now add the descriptor directory to the maven resources
        final String ourRsrcPath = this.outputDirectory.getAbsolutePath();
        boolean found = false;
        @SuppressWarnings("unchecked")
        final Iterator<Resource> rsrcIterator = this.project.getResources().iterator();
        while ( !found && rsrcIterator.hasNext() )
        {
            final Resource rsrc = rsrcIterator.next();
            found = rsrc.getDirectory().equals( ourRsrcPath );
        }
        if ( !found )
        {
            final Resource resource = new Resource();
            resource.setDirectory( this.outputDirectory.getAbsolutePath() );
            this.project.addResource( resource );
        }
    }
}