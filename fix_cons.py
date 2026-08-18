import sys

p = 'buildSrc/src/main/kotlin/io/eve/ktannot/gen/Scanner.kt'
with open(p) as f:
    s = f.read()

# Fix: resolveType should handle already-resolved generic types
# Cons<QuadTreeObject> -> base Cons is not in nameToFqn, but "Cons" is in knownFqn
# The issue is that when type is "Cons" (simple name), it gets resolved to "arc.func.Cons"
# But when type is "Cons<QuadTreeObject>", the generic parsing happens
# and base "Cons" resolves to "arc.func.Cons", inner "QuadTreeObject" resolves to
# "arc.math.geom.QuadTree.QuadTreeObject", giving "arc.func.Cons<arc.math.geom.QuadTree.QuadTreeObject>"
# Then the entity generator's typeName() sees "<" and strips generics, leaving "arc.func.Cons"
# which is a raw type. KotlinPoet then generates "Cons" without type args, causing "One type argument expected"

# Solution: in the entity generator's typeName(), handle generic types with ParamerizedTypeName
# Instead of stripGenerics, keep the full type string and parse it properly

# Actually, the simplest fix: in the EntityGenerator.typeName(), when we see a generic type,
# use ClassName.bestGuess which handles ParameterizedTypeName in KotlinPoet 1.17.0
# Let me check if ClassName.bestGuess handles generics...

# The problem is that stripGenerics removes ALL generic type info, leaving just "Cons"
# But Kotlin needs "Cons<QuadTreeObject>" or "Cons<*>"

# Fix: in stripGenerics, replace generic params with * instead of removing them
p2 = 'buildSrc/src/main/kotlin/io/eve/ktannot/gen/EntityGenerator.kt'
with open(p2) as f:
    s2 = f.read()

old = '    private fun stripGenerics(s: String): String {\n        val sb = StringBuilder()\n        var depth = 0\n        for (c in s) {\n            when (c) {\n                \'<\' -> depth++\n                \'>\' -> depth--\n                else -> if (depth == 0) sb.append(c)\n            }\n        }\n        return sb.toString()\n    }'
new = '    private fun stripGenerics(s: String): String {\n        // 保留泛型骨架: Cons<QuadTree<QuadTreeObject>> -> Cons<*>
        val sb = StringBuilder()\n        var depth = 0\n        var needStar = false\n        for (c in s) {\n            when (c) {\n                \'<\' -> { if (depth == 0) { sb.append("<*>"); needStar = true }; depth++ }\n                \'>\' -> depth--\n                else -> if (depth == 0) sb.append(c)\n            }\n        }\n        return sb.toString()\n    }'
s2 = s2.replace(old, new)

# Also remove the bestGuess call that can't handle generics
# Actually, ClassName.bestGuess("arc.func.Cons<*>") should work in KotlinPoet 1.17.0
# Let me check the generated code...

with open(p2, 'w') as f:
    f.write(s2)

print("Done")