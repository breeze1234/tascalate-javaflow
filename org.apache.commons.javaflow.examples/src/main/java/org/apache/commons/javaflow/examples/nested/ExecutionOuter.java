package org.apache.commons.javaflow.examples.nested;

import org.apache.commons.javaflow.api.continuable;
import org.apache.commons.javaflow.api.Continuation;

public class ExecutionOuter implements Runnable {
	
	@Override
	public @continuable void run() {
		for (int i = 1; i <= 5; i++) {
			System.out.println("Execution " + i);
			for (Continuation cc = Continuation.startWith(new ExecutionInner(i)); null != cc; cc = cc.resume()) {
				
				System.out.println("\tFrom inner " + cc.value());
				Continuation.suspend(i);
			}
		}
	}

}
