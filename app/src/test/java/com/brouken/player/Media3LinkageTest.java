package com.brouken.player;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Does the ExoPlayer in libs/ still have the methods the published Media3
 * modules call?
 *
 * ExoPlayer does not come from Maven here. It comes from
 * libs/lib-exoplayer-release.aar, a patched build, and libs/lib-ui-release.aar
 * likewise — the player needs setMapDV7ToHevc, showProgress and
 * hideControllerImmediately, which upstream has not got. Every other Media3
 * module does come from Maven, and those modules are compiled against the
 * ExoPlayer of their own version.
 *
 * So the version in build.gradle and the version those aars were built from
 * have to agree, and nothing enforces it. Nothing warns at build time either:
 * the modules are separate artefacts, so javac and R8 both accept a call to a
 * method that is not there, and the program only finds out when it runs the
 * line.
 *
 * That happened. media3_version was raised to 1.11.0 while the aars stayed at
 * 1.10.0, and HlsMediaSource 1.11.0 calls BaseMediaSource.getBandwidthMeter(),
 * added after 1.10.0. Every HLS stream then killed the playback thread with
 * NoSuchMethodError. What you saw was a player that opened, showed the title,
 * and sat at 00:00 — no error, no dialog, nothing in the interface at all to
 * say the stream had died. It would have shipped.
 *
 * This reads the class files of every Media3 jar there is — the published
 * modules and the aars both — picks out every method and field they name in
 * one another, and checks each one is really there. It is the same question
 * the virtual machine asks when it reaches the line, asked here instead, where
 * the answer costs a second rather than a stream at midnight.
 */
public class Media3LinkageTest {

    /** Every Media3 package, wherever the class holding it came from. */
    private static final String[] MEDIA3 = {
            "androidx/media3/",
    };

    @Test
    public void everyPublishedModuleLinksAgainstTheBundledExoPlayer() throws Exception {
        final List<File> modules = media3Jars();

        // If this cannot see the jars it is not proving anything, and should
        // say so rather than pass quietly.
        assertTrue("no Media3 jars on the test classpath — this test "
                        + "is not checking anything; classpath was:\n" + classpath(),
                modules.size() >= 8);

        final Set<String> broken = new LinkedHashSet<>();
        int callsChecked = 0;

        for (final File module : modules) {
            try (ZipFile zip = new ZipFile(module)) {
                final Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    final ZipEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    final byte[] bytes = read(zip.getInputStream(entry));
                    for (final Call call : callsInto(bytes, MEDIA3)) {
                        callsChecked++;
                        final String why = whyItWouldNotLink(call);
                        if (why != null) {
                            broken.add(call.owner.replace('/', '.') + "." + call.name
                                    + call.descriptor
                                    + "\n        " + why
                                    + "\n        called from " + entry.getName()
                                    + "\n        in " + moduleOf(module.getPath()));
                        }
                    }
                }
            }
        }

        assertTrue("no calls between Media3 modules were found at all, so "
                + "nothing was checked", callsChecked > 0);

        if (!broken.isEmpty()) {
            final StringBuilder message = new StringBuilder();
            message.append("The Media3 modules from Maven and the ExoPlayer in libs/ are ")
                    .append("different versions.\n\n")
                    .append("These calls would throw NoSuchMethodError, or ")
                    .append("NoSuchFieldError, at the moment they run:\n\n");
            for (final String one : broken) {
                message.append("  - ").append(one).append("\n");
            }
            message.append("\nEither put media3_version in app/build.gradle back to the ")
                    .append("version\nthe aars in libs/ were built from, or rebuild those ")
                    .append("aars from the\nmatching AndroidX Media tag with the patches ")
                    .append("applied.\n")
                    .append("\n(").append(callsChecked)
                    .append(" calls checked across ").append(modules.size())
                    .append(" modules.)");
            fail(message.toString());
        }
    }

    // ------------------------------------------------------------- classpath

    private static String classpath() {
        return System.getProperty("java.class.path", "");
    }

    /**
     * Every jar that holds Media3 code: the published modules, and the aars in
     * libs/ that ExoPlayer, the player view and the decoders come from.
     *
     * Both sides are read, because the calls go both ways. The published
     * modules call into ExoPlayer — that is the direction that broke — and
     * ExoPlayer and the decoder extensions call back out into media3-common,
     * -decoder, -extractor and -datasource, which are published. A mismatch
     * shows up as a missing method in whichever direction happens to be
     * looking, so neither direction is worth checking alone.
     *
     * Gradle puts each one on the classpath twice, in two different shapes, so
     * they are gathered by module name and taken once.
     */
    private static List<File> media3Jars() {
        final List<File> found = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (final String element : classpath().split(File.pathSeparator)) {
            final String module = moduleOf(element);
            final boolean published = module.startsWith("media3-");
            final boolean bundled = module.equals("lib-exoplayer-release")
                    || module.equals("lib-ui-release")
                    || module.startsWith("lib-decoder-");
            if (!published && !bundled) {
                continue;
            }
            final File file = new File(element);
            if (file.isFile() && seen.add(module)) {
                found.add(file);
            }
        }
        return found;
    }

    /**
     * Which module a classpath entry belongs to.
     *
     * Gradle hands these over in two shapes, and neither is laid out by group:
     *   .../transformed/jetified-media3-exoplayer-hls-1.10.0-runtime.jar
     *   .../transformed/jetified-media3-exoplayer-hls-1.10.0/jars/classes.jar
     * Both answer "media3-exoplayer-hls".
     */
    private static String moduleOf(final String element) {
        String path = element.replace('\\', '/');
        if (path.endsWith("/jars/classes.jar")) {
            path = path.substring(0, path.length() - "/jars/classes.jar".length());
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - ".jar".length());
        }
        if (name.startsWith("jetified-")) {
            name = name.substring("jetified-".length());
        }
        if (name.endsWith("-runtime")) {
            name = name.substring(0, name.length() - "-runtime".length());
        }
        // Trim the trailing version, so "media3-exoplayer-hls-1.10.0" is one
        // module however it is spelled on the classpath.
        final int dash = name.lastIndexOf('-');
        if (dash > 0 && dash + 1 < name.length()
                && Character.isDigit(name.charAt(dash + 1))) {
            name = name.substring(0, dash);
        }
        return name;
    }

    // --------------------------------------------------------- does it exist

    /**
     * Why this call would not link, or null if it would.
     *
     * Both answers matter. A method that has been added since is the case that
     * bit; a whole class that has been added since is the same mistake showing
     * a different face, and is just as fatal at the moment it runs.
     */
    private static String whyItWouldNotLink(final Call call) {
        final String owner = call.owner.replace('/', '.');
        final Class<?> target;
        try {
            target = Class.forName(owner, false, Media3LinkageTest.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError absent) {
            return "no such class — " + owner + " is not in the aar at all";
        } catch (Throwable unreadable) {
            // Something else stopped it being read. Not evidence of a fault.
            return null;
        }

        try {
            if ("<init>".equals(call.name) || "<clinit>".equals(call.name)) {
                for (final java.lang.reflect.Constructor<?> made
                        : target.getDeclaredConstructors()) {
                    if (descriptorOf(made.getParameterTypes(), void.class)
                            .equals(call.descriptor)) {
                        return null;
                    }
                }
                return "no such constructor";
            }

            for (final Class<?> here : hierarchyOf(target)) {
                for (final java.lang.reflect.Method method : here.getDeclaredMethods()) {
                    if (method.getName().equals(call.name)
                            && descriptorOf(method.getParameterTypes(),
                                    method.getReturnType()).equals(call.descriptor)) {
                        return null;
                    }
                }
                if (call.field) {
                    for (final java.lang.reflect.Field field : here.getDeclaredFields()) {
                        if (field.getName().equals(call.name)
                                && typeOf(field.getType()).equals(call.descriptor)) {
                            return null;
                        }
                    }
                }
            }
        } catch (NoClassDefFoundError incomplete) {
            // If what is missing is another Media3 class, that is the fault
            // being looked for. If it is something else, it is only absent
            // from this JVM — the framework classes here are stubs, and a
            // class touching OpenGL cannot be read through on a laptop. That
            // is a limit of the test, not a defect in the player.
            final String missing = String.valueOf(incomplete.getMessage());
            if (missing.startsWith("androidx/media3/")) {
                return "cannot be read — " + owner + " needs " + missing
                        + ", which is not in the aar";
            }
            return null;
        } catch (Throwable unreadable) {
            return null;
        }
        return call.field ? "no such field" : "no such method";
    }

    private static List<Class<?>> hierarchyOf(final Class<?> start) {
        final List<Class<?>> all = new ArrayList<>();
        final List<Class<?>> queue = new ArrayList<>();
        queue.add(start);
        // An interface has no superclass, but equals, hashCode, toString and
        // getClass can still be called through one — the compiler writes the
        // interface as the owner and the virtual machine finds them on Object.
        // Without this, every such call read as a missing method.
        queue.add(Object.class);
        while (!queue.isEmpty()) {
            final Class<?> here = queue.remove(0);
            if (here == null || all.contains(here)) {
                continue;
            }
            all.add(here);
            try {
                queue.add(here.getSuperclass());
                queue.addAll(Arrays.asList(here.getInterfaces()));
            } catch (Throwable incomplete) {
                // Leave the hierarchy as far as it could be read.
            }
        }
        return all;
    }

    private static String descriptorOf(final Class<?>[] parameters, final Class<?> returns) {
        final StringBuilder out = new StringBuilder("(");
        for (final Class<?> parameter : parameters) {
            out.append(typeOf(parameter));
        }
        return out.append(')').append(typeOf(returns)).toString();
    }

    private static String typeOf(final Class<?> type) {
        if (type.isArray()) {
            return "[" + typeOf(type.getComponentType());
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        if (type == void.class)    return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class)    return "B";
        if (type == char.class)    return "C";
        if (type == short.class)   return "S";
        if (type == int.class)     return "I";
        if (type == long.class)    return "J";
        if (type == float.class)   return "F";
        return "D";
    }

    // ------------------------------------------------- reading a class file

    private static final class Call {
        final String owner;
        final String name;
        final String descriptor;
        final boolean field;

        Call(String owner, String name, String descriptor, boolean field) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.field = field;
        }
    }

    private static final int UTF8 = 1, INTEGER = 3, FLOAT = 4, LONG = 5, DOUBLE = 6,
            CLASS = 7, STRING = 8, FIELD_REF = 9, METHOD_REF = 10, INTERFACE_METHOD_REF = 11,
            NAME_AND_TYPE = 12, METHOD_HANDLE = 15, METHOD_TYPE = 16, DYNAMIC = 17,
            INVOKE_DYNAMIC = 18, MODULE = 19, PACKAGE = 20;

    /**
     * Every method and field a class file names in one of the given packages.
     *
     * Only the constant pool is read. That is where the references live — the
     * owner, the name, and the exact signature — and reading it needs no
     * library and no version of anything.
     */
    private static List<Call> callsInto(final byte[] classFile, final String[] packages)
            throws IOException {
        final List<Call> calls = new ArrayList<>();
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));

        if (in.readInt() != 0xCAFEBABE) {
            return calls;
        }
        in.readUnsignedShort();                       // minor
        in.readUnsignedShort();                       // major
        final int count = in.readUnsignedShort();

        final String[] strings = new String[count];
        final int[][] refs = new int[count][];        // ref -> {classIndex, nameAndType}
        final int[] classNames = new int[count];      // class -> utf8 index
        final int[][] nameAndTypes = new int[count][];
        final boolean[] isField = new boolean[count];

        for (int i = 1; i < count; i++) {
            final int tag = in.readUnsignedByte();
            switch (tag) {
                case UTF8:
                    strings[i] = in.readUTF();
                    break;
                case CLASS:
                case STRING:
                case METHOD_TYPE:
                case MODULE:
                case PACKAGE:
                    classNames[i] = in.readUnsignedShort();
                    break;
                case FIELD_REF:
                case METHOD_REF:
                case INTERFACE_METHOD_REF:
                    refs[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                    isField[i] = tag == FIELD_REF;
                    break;
                case NAME_AND_TYPE:
                    nameAndTypes[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                    break;
                case INTEGER:
                case FLOAT:
                case DYNAMIC:
                case INVOKE_DYNAMIC:
                    in.readInt();
                    break;
                case LONG:
                case DOUBLE:
                    in.readLong();
                    i++;                              // takes two entries
                    break;
                case METHOD_HANDLE:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    // A tag from a newer class file format than this knows.
                    return calls;
            }
        }

        for (int i = 1; i < count; i++) {
            if (refs[i] == null) {
                continue;
            }
            final int classEntry = refs[i][0];
            final int nameAndType = refs[i][1];
            if (classEntry <= 0 || classEntry >= count || nameAndType <= 0
                    || nameAndType >= count || nameAndTypes[nameAndType] == null) {
                continue;
            }
            final String owner = strings[classNames[classEntry]];
            if (owner == null || !startsWithAny(owner, packages)) {
                continue;
            }
            final String name = strings[nameAndTypes[nameAndType][0]];
            final String descriptor = strings[nameAndTypes[nameAndType][1]];
            if (name != null && descriptor != null) {
                calls.add(new Call(owner, name, descriptor, isField[i]));
            }
        }
        return calls;
    }

    private static boolean startsWithAny(final String value, final String[] prefixes) {
        for (final String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] read(final InputStream in) throws IOException {
        try {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int got;
            while ((got = in.read(chunk)) > 0) {
                out.write(chunk, 0, got);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
