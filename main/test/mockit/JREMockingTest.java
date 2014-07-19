/*
 * Copyright (c) 2006-2014 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.*;

import org.junit.*;
import static org.junit.Assert.*;

@SuppressWarnings({
   "WaitWhileNotSynced", "UnconditionalWait", "WaitWithoutCorrespondingNotify", "WaitNotInLoop",
   "WaitOrAwaitWithoutTimeout", "unused", "deprecation"})
public final class JREMockingTest
{
   @Test
   public void mockingOfFile(@Mocked final File file)
   {
      new NonStrictExpectations() {{
         file.exists(); result = true;
      }};

      File f = new File("...");
      assertTrue(f.exists());
   }

   @Test
   public void mockingOfCalendar()
   {
      final Calendar calCST = new GregorianCalendar(2010, 4, 15);

      new NonStrictExpectations(Calendar.class) {{
         Calendar.getInstance(TimeZone.getTimeZone("CST")); result = calCST;
      }};

      Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("CST"));
      assertSame(calCST, cal);
      assertEquals(2010, cal.get(Calendar.YEAR));

      assertNotSame(calCST, Calendar.getInstance(TimeZone.getTimeZone("PST")));
   }

   @Test
   public void regularMockingOfAnnotatedJREMethod(@Mocked Date d) throws Exception
   {
      assertTrue(d.getClass().getDeclaredMethod("parse", String.class).isAnnotationPresent(Deprecated.class));
   }

   @Test
   public void dynamicMockingOfAnnotatedJREMethod() throws Exception
   {
      final Date d = new Date();

      new NonStrictExpectations(d) {{
         d.getMinutes(); result = 5;
      }};

      assertEquals(5, d.getMinutes());
      assertTrue(Date.class.getDeclaredMethod("getMinutes").isAnnotationPresent(Deprecated.class));
   }

   @Test
   public void fullMockingOfSystem(@Mocked System mockSystem)
   {
      new NonStrictExpectations() {{
         System.currentTimeMillis();
         result = 123L;
      }};

      assertEquals(123L, System.currentTimeMillis());
   }

   @Test
   public void partialMockingOfSystem() throws Exception
   {
      long t0 = System.currentTimeMillis();

      new MockUp<System>() {
         @Mock long nanoTime() { return 123L; }
      };

      // Repeat enough times to pass the JVM inflation threshold, causing a bytecode accessor to be generated.
      for (int i = 0; i < 50; i++) {
         assertEquals(123L, System.nanoTime());
      }

      long delay = 40;
      Thread.sleep(delay);
      long t1 = System.currentTimeMillis();
      assertTrue(t1 - t0 >= delay);
   }

   // Mocking of java.lang.Thread and native methods //////////////////////////////////////////////////////////////////

   // First mocking: puts mocked class in cache, knowing it has native methods to re-register.
   @Test
   public void firstMockingOfNativeMethods(@Mocked("sleep") Thread unused) throws Exception
   {
      Thread.sleep(5000);
   }

   // Second mocking: retrieves from cache, no longer knowing it has native methods to re-register.
   @Test
   public void secondMockingOfNativeMethods(@Mocked("isAlive") final Thread mock)
   {
      new Expectations() {{
         mock.isAlive(); result = true;
      }};

      assertTrue(mock.isAlive());
   }

   @Test
   public void unmockedNativeMethods() throws Exception
   {
      Thread.sleep(10);
      assertTrue(System.currentTimeMillis() > 0);
   }

   // See http://www.javaspecialists.eu/archive/Issue056.html
   public static class InterruptibleThread extends Thread
   {
      protected final boolean wasInterruptRequested()
      {
         try {
            Thread.sleep(10);
            return false;
         }
         catch (InterruptedException ignore) {
            interrupt();
            return true;
         }
      }
   }

   @Test
   public void interruptibleThreadShouldResetItsInterruptStatusWhenInterrupted(
      @Mocked({"sleep", "interrupt"}) Thread unused) throws Exception
   {
      final InterruptibleThread t = new InterruptibleThread();

      new Expectations() {{
         Thread.sleep(anyLong); result = new InterruptedException();
         onInstance(t).interrupt();
      }};

      assertTrue(t.wasInterruptRequested());
   }

   static class ExampleInterruptibleThread extends InterruptibleThread
   {
      boolean terminatedCleanly;

      @Override
      public void run()
      {
         while (true) {
            for (int i = 0; i < 10; i++) {
               if (wasInterruptRequested()) break;
            }

            if (wasInterruptRequested()) break;
         }

         terminatedCleanly = true;
      }
   }

   @Test
   public void interruptionOfThreadRunningNestedLoops() throws Exception
   {
      ExampleInterruptibleThread t = new ExampleInterruptibleThread();
      t.start();
      Thread.sleep(30);
      t.interrupt();
      t.join();
      assertTrue(t.terminatedCleanly);
   }

   // When a native instance method is called on a regular instance, there is no way to execute its real
   // implementation; therefore, dynamic mocking of native methods is not supported.
   @Test
   public void dynamicMockingOfNativeMethod(@Injectable final Thread t)
   {
      new NonStrictExpectations() {{
         t.isAlive();

         try {
            result = true;
            fail();
         }
         catch (IllegalStateException ignore) {
            // OK
         }
      }};
   }

   @Test
   public void fullMockingOfThread(@Mocked Thread t)
   {
      new NonStrictExpectations() {{
         Thread.activeCount();
         result = 123;
      }};

      assertEquals(123, Thread.activeCount());

      new Verifications() {{
         new Thread((Runnable) any); times = 0;
      }};
   }

   @Test
   public void dynamicMockingOfThread()
   {
      final Thread d = new Thread((Runnable) null);

      new NonStrictExpectations(d) {};

      d.start();
      d.interrupt();

      new Verifications() {{
         d.start(); times = 1;
         d.interrupt();
      }};
   }

   @Test
   public void mockingOfAnnotatedNativeMethod(@Mocked("countStackFrames") Thread mock) throws Exception
   {
      assertTrue(Thread.class.getDeclaredMethod("countStackFrames").isAnnotationPresent(Deprecated.class));
   }

   static final class SomeTask extends Thread { boolean doSomething() { return false; } }

   @Test
   public void recordDelegatedResultForMethodInMockedThreadSubclass(@Mocked final SomeTask task)
   {
      new NonStrictExpectations() {{
         task.doSomething();
         result = new Delegate() {
            @SuppressWarnings("unused")
            boolean doIt() { return true; }
         };
      }};

      assertTrue(task.doSomething());
   }

   @Injectable FileOutputStream stream;

   // This interferes with the test runner if regular mocking is applied.
   @Test
   public void dynamicMockingOfFileOutputStreamThroughMockField() throws Exception
   {
      new Expectations() {{
         stream.write((byte[]) any);
      }};

      stream.write("Hello world".getBytes());
   }

   // Mocking of java.lang.Object methods /////////////////////////////////////////////////////////////////////////////

   final Object lock = new Object();

   void awaitNotification() throws InterruptedException
   {
      synchronized (lock) {
         lock.wait();
      }
   }

   @Test
   public void waitingWithDynamicPartialMocking() throws Exception
   {
      final Object mockedLock = new Object();

      new Expectations(Object.class) {{ mockedLock.wait(); }};

      awaitNotification();
   }

   @Test
   public void waitingWithMockParameter(@Mocked final Object mockedLock) throws Exception
   {
      new NonStrictExpectations() {{
         mockedLock.wait(); minTimes = 1;
      }};

      awaitNotification();
   }

   // Mocking the Reflection API //////////////////////////////////////////////////////////////////////////////////////

   @Retention(RetentionPolicy.RUNTIME) @interface AnAnnotation { String value(); }
   @Retention(RetentionPolicy.RUNTIME) @interface AnotherAnnotation {}
   enum AnEnum { @AnAnnotation("one") First, @AnAnnotation("two") Second, @AnotherAnnotation Third }

   @Test
   public void mockingOfGetAnnotation() throws Exception
   {
      new MockUp<Field>() {
         final Map<Object, Annotation> annotationsApplied = new HashMap<Object, Annotation>() {{
            put(AnEnum.First, anAnnotation("1"));
            put(AnEnum.Second, anAnnotation("2"));
         }};

         AnAnnotation anAnnotation(final String value)
         {
            return new AnAnnotation() {
               @Override public Class<? extends Annotation> annotationType() { return AnAnnotation.class; }
               @Override public String value() { return value; }
            };
         }

         @Mock
         <T extends Annotation> T getAnnotation(Invocation inv, Class<T> annotation) throws IllegalAccessException
         {
            Field it = inv.getInvokedInstance();
            Object fieldValue = it.get(null);
            Annotation value = annotationsApplied.get(fieldValue);

            if (value != null) {
               //noinspection unchecked
               return (T) value;
            }

            return inv.proceed();
         }
      };

      Field firstField = AnEnum.class.getField(AnEnum.First.name());
      AnAnnotation annotation1 = firstField.getAnnotation(AnAnnotation.class);
      assertEquals("1", annotation1.value());

      Field secondField = AnEnum.class.getField(AnEnum.Second.name());
      AnAnnotation annotation2 = secondField.getAnnotation(AnAnnotation.class);
      assertEquals("2", annotation2.value());

      Field thirdField = AnEnum.class.getField(AnEnum.Third.name());
      assertNull(thirdField.getAnnotation(AnAnnotation.class));
      assertNotNull(thirdField.getAnnotation(AnotherAnnotation.class));
   }

   // Un-mockable JRE classes /////////////////////////////////////////////////////////////////////////////////////////

   @Test
   public void attemptToDynamicallyMockJREClassThatIsNeverMockable() throws Exception
   {
      try {
         new NonStrictExpectations(ClassLoader.class) {{
            String.class.getClassLoader().getResourceAsStream("resource");
         }};
         fail();
      }
      catch (IllegalArgumentException e) {
         assertTrue(e.getMessage().contains("java.lang.ClassLoader"));
      }
   }

   // Mocking java.util.logging ///////////////////////////////////////////////////////////////////////////////////////

   @Test
   public void mockLogManager(@Mocked LogManager mock)
   {
      LogManager logManager = LogManager.getLogManager();
      assertNull(logManager);
   }

   @Test
   public void mockLogger(@Mocked Logger mock)
   {
      assertNotNull(LogManager.getLogManager());
      assertNull(Logger.getLogger("test"));
   }

   // Mocking critical collection classes /////////////////////////////////////////////////////////////////////////////

   @SuppressWarnings("CollectionDeclaredAsConcreteClass")
   @Mocked final ArrayList<String> mockedArrayList = new ArrayList<String>();

   @Test
   public void useMockedArrayList()
   {
      assertTrue(mockedArrayList.add("test"));
      assertEquals("test", mockedArrayList.get(0));

      List<Object> l2 = new ArrayList<Object>();
      assertTrue(l2.add("test"));
      assertNotNull(l2.get(0));
   }

   @Test
   public void useMockedHashMap(@Mocked HashMap<String, Object> mockedHashMap)
   {
      Map<String, Object> m = new HashMap<String, Object>();
      m.put("test", 123);
      assertEquals(123, m.get("test"));
      assertEquals(1, m.size());
   }

   @Test
   public void useMockedHashSet(@Mocked final HashSet<String> mockedHashSet)
   {
      Set<String> s = new HashSet<String>();
      assertTrue(s.add("test"));
      assertFalse(s.contains("test"));
      assertEquals(0, s.size());

      new NonStrictExpectations() {{
         mockedHashSet.size();
         result = 15;
      }};

      assertEquals(15, mockedHashSet.size());
   }
}