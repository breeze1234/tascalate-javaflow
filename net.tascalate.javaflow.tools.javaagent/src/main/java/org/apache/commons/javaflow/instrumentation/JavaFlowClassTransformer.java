/**
 * ﻿Copyright 2013-2019 Valery Silaev (http://vsilaev.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.javaflow.instrumentation;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;

import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.javaflow.spi.ClasspathResourceLoader;
import org.apache.commons.javaflow.spi.ContinuableClassInfoResolver;
import org.apache.commons.javaflow.spi.ExtendedClasspathResourceLoader;
import org.apache.commons.javaflow.spi.ResourceTransformationFactory;
import org.apache.commons.javaflow.spi.ResourceTransformer;

import org.apache.commons.javaflow.providers.asmx.AsmxResourceTransformationFactory;

public class JavaFlowClassTransformer implements ClassFileTransformer {
    private static final Logger log = LoggerFactory.getLogger(JavaFlowClassTransformer.class);

    private final ResourceTransformationFactory resourceTransformationFactory = new AsmxResourceTransformationFactory();
    private final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

    // @Override
    public byte[] transform(ClassLoader classLoader, 
                            String className, 
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, 
                            final byte[] classfileBuffer) throws IllegalClassFormatException {

        if (isSystemClassLoaderParent(classLoader)) {
            if (log.isDebugEnabled()) {
                log.info("Ignoring class defined by boot or extensions/platform class loader: " + className);
            }
            return null;
        }

        // Ensure classLoader is not null (null for boot class loader)
        classLoader = getSafeClassLoader(classLoader);
        final ContinuableClassInfoResolver resolver = getCachedResolver(classLoader);

        // Ensure className is not null (parameter is null for dynamically defined classes like lambdas)
        className = resolveClassName(resolver, className, classBeingRedefined, classfileBuffer);
        try {
            // Execute with current class as extra resource (in-memory)
            // Mandatory for Java8 lambdas and alike
            final String cn = className;
            return ExtendedClasspathResourceLoader.runWithInMemoryResources(
                new Callable<byte[]>() {
                    public byte[] call() {
                        ResourceTransformer transformer = 
                            resourceTransformationFactory.createTransformer(resolver);
                        
                        synchronized (resolver) {
                            resolver.forget(cn);
                            return transformer.transform(classfileBuffer);                                
                        }
                    }
                }, 
                Collections.singletonMap(className + ".class", classfileBuffer)
            );
        } catch (RuntimeException ex) {
            if (log.isErrorEnabled()) {
                if (VERBOSE_ERROR_REPORTS) {
                    log.error("Error transforming " + className, ex);
                } else {
                    log.error("Error transforming " + className);
                }
            }
            return null;
        } catch (ClassCircularityError ex) {
            if (VERBOSE_ERROR_REPORTS && log.isWarnEnabled()) {
                log.warn("Ignoring class circularity error: " + ex.getMessage());
            }
            return null;
        } catch (final Error ex) {
            log.error("Internal error during transforming continuable class", ex);
            throw ex;
        }
    }

    protected ClassLoader getSafeClassLoader(ClassLoader classLoader) {
        return null != classLoader ? classLoader : systemClassLoader;
    }

    protected ContinuableClassInfoResolver getCachedResolver(ClassLoader classLoader) {
        synchronized (classLoader2resolver) {
            ContinuableClassInfoResolver cachedResolver = classLoader2resolver.get(classLoader);
            if (null == cachedResolver) {
                log.debug("Create classInfoResolver for class loader " + classLoader);
                ContinuableClassInfoResolver newResolver = resourceTransformationFactory.createResolver(
                    new ExtendedClasspathResourceLoader(classLoader)
                );
                classLoader2resolver.put(classLoader, newResolver);
                return newResolver;
            } else {
                return cachedResolver;
            }
        }
    }

    private boolean isSystemClassLoaderParent(ClassLoader maybeParent) {
        return ClasspathResourceLoader.isClassLoaderParent(systemClassLoader, maybeParent);
    }
    
    private static String resolveClassName(ContinuableClassInfoResolver resolver,
                                           String className,
                                           Class<?> classBeingRedefined,
                                           byte[] classfileBuffer) {
        if (null != className) {
            return className;
        } else if (classBeingRedefined != null) {
            return classBeingRedefined.getName().replace('.', '/');
        } else {
            return resolver.readClassName(classfileBuffer);
        }
    }    

    private static final Map<ClassLoader, ContinuableClassInfoResolver> classLoader2resolver = new WeakHashMap<ClassLoader, ContinuableClassInfoResolver>();
    private static final boolean VERBOSE_ERROR_REPORTS = Boolean.getBoolean("org.apache.commons.javaflow.instrumentation.verbose");
}
