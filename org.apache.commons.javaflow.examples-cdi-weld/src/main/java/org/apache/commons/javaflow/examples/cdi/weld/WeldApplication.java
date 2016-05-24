package org.apache.commons.javaflow.examples.cdi.weld;

import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.apache.commons.javaflow.api.Continuation;
import org.jboss.weld.bean.proxy.ProxyObject;
import org.jboss.weld.environment.se.events.ContainerInitialized;

public class WeldApplication {

    @Inject 
    Execution execution;

    public void run(@Observes ContainerInitialized event) {
    	System.out.println("Execution is proxy? " + (execution instanceof ProxyObject));
        int i = 0;
        for (Continuation cc = Continuation.startWith(execution); null != cc; cc = cc.resume(i += 100)) {
            System.out.println("SUSPENDED " + cc.value());
        }

        System.out.println("===");


    }
}