///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.ow2.asm:asm:9.8

// Standalone preprocessor for the host Telegram.jar.
//
// The jar is derived from the host APK's dex (dex2jar-style). In dex, inner-class
// relationships live in per-class dalvik annotations, so the converter only
// restores the *self* entry in each class file's InnerClasses attribute and drops
// the entries javac would emit for every *referenced* nested type (superclass,
// interfaces, field/method types). Kotlin's class-file reader then can't rebuild
// the nested ClassId of the superclass, reports MISSING_DEPENDENCY_SUPERCLASS and
// hides inherited members (forcing `x as TLRPC.MessageAction` casts). This tool
// recomputes the full InnerClasses table from the references visible in each class
// and, at the same time, keeps only the host API packages needed on the compile
// classpath.
//
// Run when refreshing the host jar, then commit the output:
//   jbang tools/FixTelegramJar.java libs/Telegram.jar libs/Telegram-compile.jar
// Or without jbang (needs an asm jar on the classpath):
//   java -cp asm-9.8.jar tools/FixTelegramJar.java libs/Telegram.jar libs/Telegram-compile.jar

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class FixTelegramJar {

    // The compile jar is compileOnly (never dexed), so it can be broad: include
    // every host class so Kotlin/IDE can resolve full supertype chains (e.g.
    // ActionBar implements me.vkryl...FactorAnimator$Target). Only drop packages
    // that already come from declared Gradle deps, so their versions win on the
    // compile classpath and there are no duplicate classes. This is deliberately
    // NOT the same set as TELEGRAM_COMPILE_PACKAGE_PREFIXES in build.gradle.kts,
    // which is the narrow *relocation-exclusion* list (runtime concern).
    // Every androidx/kotlin package on the module's declared compile classpath
    // (kotlin-stdlib, coroutines, room + its transitives, recyclerview + its
    // transitives). Their host-bundled copies must be dropped so the declared
    // artifact versions win — otherwise API skew breaks compilation (e.g. the
    // host androidx.sqlite exposing a different SupportSQLiteDatabase.query).
    // Sourced from `gradle :dependencies --configuration debugCompileClasspath`.
    static final String[] EXCLUDED_PREFIXES = {
        "kotlin/",
        "kotlinx/coroutines/",
        "androidx/annotation/",
        "androidx/arch/",
        "androidx/collection/",
        "androidx/core/",
        "androidx/customview/",
        "androidx/lifecycle/",
        "androidx/recyclerview/",
        "androidx/room/",
        "androidx/sqlite/",
        "androidx/versionedparcelable/",
        "com/android/tools/r8/",
    };

    // Extracts `Lorg/telegram/.../TLRPC$Foo;`-style references from a type
    // descriptor or generic signature; group 1 is the internal name.
    static final Pattern TYPE_DESCRIPTOR = Pattern.compile("L([^;<]+)");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: FixTelegramJar <input.jar> <output.jar>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);

        // Real access flags for every class, so recomputed InnerClasses entries
        // carry the correct modifiers of the referenced nested types.
        Map<String, Integer> accessMap = buildAccessMap(in);

        int kept = 0;
        try (ZipFile zip = new ZipFile(in);
             ZipOutputStream zos = new ZipOutputStream(
                 new BufferedOutputStream(new FileOutputStream(out)))) {
            zos.setLevel(Deflater.BEST_COMPRESSION);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                // Drop only packages provided by declared deps; keep everything else.
                if (isExcluded(entry.getName())) continue;

                byte[] fixed;
                try (InputStream is = zip.getInputStream(entry)) {
                    fixed = recomputeInnerClasses(is.readAllBytes(), accessMap);
                }
                zos.putNextEntry(new ZipEntry(entry.getName()));
                zos.write(fixed);
                zos.closeEntry();
                kept++;
            }
        }
        System.out.println("wrote " + out + " (" + kept + " classes)");
    }

    static boolean isExcluded(String name) {
        for (String p : EXCLUDED_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    static Map<String, Integer> buildAccessMap(File jar) throws IOException {
        Map<String, Integer> map = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                try (InputStream is = zip.getInputStream(entry)) {
                    ClassReader cr = new ClassReader(is);
                    map.put(cr.getClassName(), cr.getAccess());
                }
            }
        }
        return map;
    }

    static void addNestedChain(Set<String> set, String internalName) {
        String name = internalName;
        if (name == null) return;
        while (name.contains("$")) {
            set.add(name);
            name = name.substring(0, name.lastIndexOf('$'));
        }
    }

    static void collect(Set<String> set, String descriptor) {
        if (descriptor == null) return;
        Matcher m = TYPE_DESCRIPTOR.matcher(descriptor);
        while (m.find()) addNestedChain(set, m.group(1));
    }

    static byte[] recomputeInnerClasses(byte[] bytes, Map<String, Integer> accessMap) {
        ClassReader cr = new ClassReader(bytes);

        // Every nested type referenced by this class, plus its enclosing chain.
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        addNestedChain(referenced, cr.getClassName());
        addNestedChain(referenced, cr.getSuperName());
        for (String itf : cr.getInterfaces()) addNestedChain(referenced, itf);

        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int a, String n, String descriptor, String signature, Object v) {
                collect(referenced, descriptor);
                collect(referenced, signature);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int a, String n, String descriptor, String signature, String[] exceptions) {
                collect(referenced, descriptor);
                collect(referenced, signature);
                if (exceptions != null) for (String ex : exceptions) addNestedChain(referenced, ex);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            final Set<String> emitted = new HashSet<>();

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                emitted.add(name);
                super.visitInnerClass(name, outerName, innerName, access);
            }

            @Override
            public void visitEnd() {
                for (String name : referenced) {
                    if (!emitted.add(name)) continue;
                    String simpleName = name.substring(name.lastIndexOf('$') + 1);
                    // ACC_SUPER (from the top-level access flags) is not a valid
                    // inner_class_access_flags bit; strip it.
                    int raw = accessMap.getOrDefault(name, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
                    int access = raw & ~Opcodes.ACC_SUPER;
                    if (simpleName.isEmpty() || Character.isDigit(simpleName.charAt(0))) {
                        // Anonymous/local class: no outer or simple name (JVMS 4.7.6).
                        super.visitInnerClass(name, null, null, access);
                    } else {
                        super.visitInnerClass(
                            name,
                            name.substring(0, name.lastIndexOf('$')),
                            simpleName,
                            access | Opcodes.ACC_STATIC);
                    }
                }
                super.visitEnd();
            }
        }, 0);
        return cw.toByteArray();
    }
}
