import sys

# Fix EntityGenerator.kt
p = 'buildSrc/src/main/kotlin/io/eve/ktannot/gen/EntityGenerator.kt'
with open(p) as f:
    s = f.read()

# Fix 1: In interface generation, skip methods with generic type params
old = '        for (m in cls.methods.filter { !it.isPrivate && !it.isStatic }) {'
new = '        val nonGenericMethods = cls.methods.filter { !it.isPrivate && !it.isStatic }\n            .filter { m ->\n                val ret = m.returnType.trim().removeSuffix("?")\n                !(ret.length == 1 && ret[0].isUpperCase())\n            }\n        for (m in nonGenericMethods) {'
s = s.replace(old, new)

# Fix 2: Include @Import fields in entity
old = 'for (f in comp.cls.fields.filter { !it.annotations.containsKey("Import") }) {'
new = 'for (f in comp.cls.fields) {'
s = s.replace(old, new)

# Fix 3: In entity, add EntityCompc to supertypes
old = '        // 实体类字段 + 方法 + sync 序列化\n        // 组件接口\n        for (comp in componentList) {'
new = '        // 实体类字段 + 方法 + sync 序列化\n        // 基接口\n        componentByName["EntityComp"]?.let { base ->\n            typeBuilder.addSuperinterface(ClassName(GEN_PKG, interfaceName(base.cls)))\n        }\n        // 组件接口\n        for (comp in componentList) {'
s = s.replace(old, new)

with open(p, 'w') as f:
    f.write(s)

print("Done")