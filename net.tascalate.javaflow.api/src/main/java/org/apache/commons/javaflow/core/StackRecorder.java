/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.javaflow.core;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Adds additional behaviors necessary for stack capture/restore
 * on top of {@link Stack}.
 *
 * @author Kohsuke Kawaguchi
 */
public final class StackRecorder extends Stack {

    private static final Log log = LogFactory.getLog(StackRecorder.class);
    private static final long serialVersionUID = 2L;

    private static final ThreadLocal<StackRecorder> threadMap = new ThreadLocal<StackRecorder>();

    /**
     * True, if the continuation restores the previous stack trace to the last
     * invocation of suspend().
     *
     * <p>
     * This field is accessed from the byte code injected into application code,
     * and therefore defining a wrapper get method makes it awkward to
     * step through the user code. That's why this field is public.
     */
    public transient boolean isRestoring = false;

    /**
     * True, is the continuation freeze the strack trace, and stops the
     * continuation.
     *
     * @see #isRestoring
     */
    public transient boolean isCapturing = false;

    /** Parameter object passed by the client code to continuation during resume */
    private transient ResumeParameter parameter;
    /** Result object passed by the continuation to the client code during suspend */
    private transient SuspendResult result;

    /**
     * Creates a new empty {@link StackRecorder} that runs the given target.
     * @param target
     *       a target to run
     */
    public StackRecorder( final Runnable target ) {
        super(target);
    }

    /**
     * Creates a clone of the given {@link StackRecorder}.
     * @param parent
     *       a StackRecorder to clone
     * 
     */
    public StackRecorder(final Stack parent) {
        super(parent);
    }

    public static Object suspend(final SuspendResult value) {
        log.debug("suspend()");

        final StackRecorder stackRecorder = get();
        if(stackRecorder == null) {
            throw new IllegalStateException("No continuation is running");
        }
        final boolean needCheckExit = stackRecorder.isRestoring;
        
        stackRecorder.isCapturing = !stackRecorder.isRestoring;
        stackRecorder.isRestoring = false;
        stackRecorder.result      = value;
        
        // flow breaks here, actual return will be executed in resumed continuation
        // return in continuation to be suspended is executed as well but ignored
        if (needCheckExit) {
        	stackRecorder.parameter.checkExit();
        }
        return stackRecorder.parameter.value();
    }

    public SuspendResult execute(final ResumeParameter parameter) {
    	if (null == parameter) {
    		throw new IllegalArgumentException("ResumeContext parameter may not be null");
    	}
        final StackRecorder old = registerThread();
        try {
            isRestoring = !isEmpty(); // start restoring if we have a filled stack
            this.parameter = parameter;
            
            if (isRestoring) {
            	if (log.isDebugEnabled()) {
            		log.debug("Restoring state of " + descriptionOf(runnable));
            	}
            }
            
            log.debug("calling runnable");
            runnable.run();

            if (isCapturing) {
                if ( isEmpty() ) {
                    // if we were really capturing the stack, at least we should have
                    // one object in the reference stack. Otherwise, it usually means
                    // that the application wasn't instrumented correctly.
                    throw new IllegalStateException(
                            "Stack corruption on suspend (empty stack). "
                            + "Is " + descriptionOf(runnable) 
                            + " instrumented for javaflow?"
                   );
                }
                // top of the reference stack is the object that we'll call into
                // when resuming this continuation. we have a separate Runnable
                // for this, so throw it away
                final Object ref = popReference(); 
                if (runnable != ref) {
                	throw new IllegalStateException(
                	        "Stack corruption on suspend (invalid reference). "
                	        + "Is " + descriptionOf(runnable.getClass()) 
                	        + " instrumented for javaflow?"
                	);
                }
                return this.result;
            } else {
            	if (!isEmpty()) {
            		throw new IllegalStateException(
            		        "Stack corruption on exit (non-empty stack). "
            		        + "Is " + descriptionOf(runnable) +
            		        " instrumented for javaflow?"
            		);
            	}
                return SuspendResult.EXIT;    // nothing more to continue
            }
        } catch(final ContinuationDeath cd) {
            // this isn't an error, so no need to log
        	return SuspendResult.EXIT;
        } catch(final Error e) {
            log.error(e.getMessage(), e);
            throw e;
        } catch(final RuntimeException e) {
            log.error(e.getMessage(), e);
            throw e;
        } finally {
            this.parameter = null;
            this.result = null;
            deregisterThread(old);
        }
    }

    public static void exit() {
    	throw ContinuationDeath.INSTANCE;
    }
    
    /**
     * Access value supplied to resumed method
     * 
     * @return value passed to the resumed method (may be <code>null</code>)
     * 
     */
    public Object getContext() {
        return null == parameter ? null : parameter.value();
    }

    /**
     * Bind this stack recorder to running thread.
     */
    private StackRecorder registerThread() {
        final StackRecorder old = get();
        threadMap.set(this);
        return old;
    }

    /**
     * Unbind the current stack recorder to running thread.
     */
    private void deregisterThread(final StackRecorder old) {
        threadMap.set(old);
    }

    /**
     * Return the continuation, which is associated to the current thread.
     * @return currently associated continuation stack, or <code>null</code> if invoked outside
     * of continuation context
     */
    public static StackRecorder get() {
        return threadMap.get();
    }
    
    private final String descriptionOf(Object object) {
        return ReflectionUtils.getClassName(runnable) + "/" + ReflectionUtils.getClassLoaderName(runnable);
    }
}