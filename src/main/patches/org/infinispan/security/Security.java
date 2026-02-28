/*
 * Patched version of org.infinispan.security.Security from Infinispan 13.0.22.Final.
 *
 * The only change is in getSubject(): instead of calling Subject.getSubject(acc)
 * (which throws UnsupportedOperationException on Java 21+ and is removed in Java 24),
 * we return null when no ThreadLocal subject is set. This matches the fix applied
 * upstream in Infinispan 15.0.x.
 *
 * In embedded mode (how OpenMRS uses Infinispan for Hibernate L2 caching), there is
 * never a Subject on the AccessControlContext, so Subject.getSubject(acc) would have
 * returned null anyway on older Java versions.
 *
 * This file is compiled during the build and injected into the WAR's WEB-INF/classes/
 * directory, where the Servlet spec guarantees it takes precedence over the original
 * class in WEB-INF/lib/infinispan-core-13.0.22.Final.jar.
 *
 * Original source: https://github.com/infinispan/infinispan/blob/13.0.22.Final/core/src/main/java/org/infinispan/security/Security.java
 * Upstream fix:    https://github.com/infinispan/infinispan/blob/15.0.x/core/src/main/java/org/infinispan/security/Security.java
 */
package org.infinispan.security;

import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.security.auth.Subject;

import org.infinispan.commons.jdkspecific.CallerId;

public final class Security {

   private static final ThreadLocal<Boolean> PRIVILEGED = ThreadLocal.withInitial(() -> Boolean.FALSE);

   private static final ThreadLocal<Deque<Subject>> SUBJECT = new InheritableThreadLocal<Deque<Subject>>() {
      @Override
      protected Deque<Subject> childValue(Deque<Subject> parentValue) {
         return parentValue == null ? null : new ArrayDeque<>(parentValue);
      }
   };

   private static boolean isTrustedClass(Class<?> klass) {
      String packageName = klass.getPackage().getName();
      return packageName.startsWith("org.infinispan") ||
            packageName.startsWith("org.jboss.as.clustering.infinispan");
   }

   public static <T> T doPrivileged(PrivilegedAction<T> action) {
      if (!isPrivileged() && isTrustedClass(CallerId.getCallerClass(3))) {
         try {
            PRIVILEGED.set(true);
            return action.run();
         } finally {
            PRIVILEGED.remove();
         }
      } else {
         return action.run();
      }
   }

   public static <T> T doPrivileged(PrivilegedExceptionAction<T> action) throws PrivilegedActionException {
      if (!isPrivileged() && isTrustedClass(CallerId.getCallerClass(3))) {
         try {
            PRIVILEGED.set(true);
            return action.run();
         } catch (Exception e) {
            throw new PrivilegedActionException(e);
         } finally {
            PRIVILEGED.remove();
         }
      } else {
         try {
            return action.run();
         } catch (Exception e) {
            throw new PrivilegedActionException(e);
         }
      }
   }

   private static Deque<Subject> pre(Subject subject) {
      if (subject == null) {
         return null;
      }
      Deque<Subject> stack = SUBJECT.get();
      if (stack == null) {
         stack = new ArrayDeque<>(3);
         SUBJECT.set(stack);
      }
      stack.push(subject);
      return stack;
   }

   private static void post(Subject subject, Deque<Subject> stack) {
      if (subject != null) {
         stack.pop();
         if (stack.isEmpty()) {
            SUBJECT.remove();
         }
      }
   }

   public static void doAs(final Subject subject, final Runnable action) {
      Deque<Subject> stack = pre(subject);
      try {
         action.run();
      } finally {
         post(subject, stack);
      }
   }

   public static <T, R> R doAs(final Subject subject, Function<T, R> function, T t) {
      Deque<Subject> stack = pre(subject);
      try {
         return function.apply(t);
      } finally {
         post(subject, stack);
      }
   }

   public static <T, U, R> R doAs(final Subject subject, BiFunction<T, U, R> function, T t, U u) {
      Deque<Subject> stack = pre(subject);
      try {
         return function.apply(t, u);
      } finally {
         post(subject, stack);
      }
   }

   public static <T> T doAs(final Subject subject, final java.security.PrivilegedAction<T> action) {
      Deque<Subject> stack = pre(subject);
      try {
         return action.run();
      } finally {
         post(subject, stack);
      }
   }

   public static <T> T doAs(final Subject subject,
                            final java.security.PrivilegedExceptionAction<T> action)
         throws java.security.PrivilegedActionException {
      Deque<Subject> stack = pre(subject);
      try {
         return action.run();
      } catch (Exception e) {
         throw new PrivilegedActionException(e);
      } finally {
         post(subject, stack);
      }
   }

   public static void checkPermission(CachePermission permission) throws AccessControlException {
      if (!isPrivileged()) {
         throw new AccessControlException("Call from unprivileged code", permission);
      }
   }

   public static boolean isPrivileged() {
      return PRIVILEGED.get();
   }

   /**
    * If using {@link Security#doAs(Subject, PrivilegedAction)} or {@link Security#doAs(Subject,
    * PrivilegedExceptionAction)}, returns the {@link Subject} associated with the current thread.
    * Returns null if no subject has been set via the ThreadLocal mechanism.
    *
    * <p>PATCHED: The original Infinispan 13.0.22 code falls back to
    * {@code Subject.getSubject(AccessController.getContext())} which throws
    * {@code UnsupportedOperationException} on Java 21.0.4+ and is removed in Java 24.
    * Since OpenMRS uses Infinispan in embedded mode without security subjects, the
    * fallback always returned null anyway, so returning null directly is safe.</p>
    */
   public static Subject getSubject() {
      Deque<Subject> subjects = SUBJECT.get();
      if (subjects != null && !subjects.isEmpty()) {
         return subjects.peek();
      }
      return null;
   }

   public static Principal getSubjectUserPrincipal(Subject s) {
      if (s != null && !s.getPrincipals().isEmpty()) {
         return s.getPrincipals().iterator().next();
      }
      return null;
   }

   public static String toString(Subject subject) {
      StringBuilder sb = new StringBuilder("Subject: [");
      boolean comma = false;
      for(Principal p : subject.getPrincipals()) {
         if (comma) {
            sb.append(" ,");
         }
         sb.append(p.toString());
         comma = true;
      }
      return sb.append(']').toString();
   }
}
