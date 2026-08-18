import sys

p = 'buildSrc/src/main/kotlin/io/eve/ktannot/gen/EntityGenerator.kt'
with open(p) as f:
    s = f.read()

# Fix 1: skip @Import fields in entity
old = '// 包含 @Import 字段(实体需要实际存储这些字段)\n        for (f in comp.cls.fields) {'
new = '// 跳过 @Import 字段(由声明它们的组件提供)\n        for (f in comp.cls.fields.filter { !it.annotations.containsKey("Import") }) {'
s = s.replace(old, new)

# Fix 2: the entity needs to implement Position.set(Float, Float)
# The issue: Posc extends Position, and Position.set(Float,Float) is abstract
# The entity body for `set(x: Float, y: Float)` is included from PosComp body
# But the body uses `this.x = x; this.y = y` which are field sets
# 
# Actually looking at the generated code, `set(x: Float, y: Float)` has body from PosComp
# which is `set(x, y)` - wait, that's recursive! 
# The PosComp body is `{ set(x, y) }` which calls itself.
# Actually no, the PosComp.kt has:
#   fun set(x: Float, y: Float) {
#       this.x = x
#       this.y = y
#   }
# So the body should be `this.x = x; this.y = y` - but the body includes `{` and `}`
# which we strip. But the `this.x = x; this.y = y` uses fields x and y which are from
# PosComp - but fields should be included in the entity.

# The issue is that `set(x: Float, y: Float)` is a method that gets its body from PosComp.
# But Wait - the entity has the fields x and y. The body `this.x = x; this.y = y` should work.
# But the error says "Class 'SimpleEntity' is not abstract and does not implement abstract member 'set'"
# This means the `set(pos: Position)` method is not being generated.
# Let me check: the method signature `set(pos: Position)` has return type Unit.
# The filter for generic methods: `val ret = m.returnType.trim().removeSuffix("?")`
# Unit is not a single uppercase char, so it should pass.
# But the issue is that `set(x: Float, y: Float)` and `set(pos: Position)` are both
# in the methods map. One overrides the other because they have the same name "set".
# The LinkedHashMap keeps only the last one (or first one) with the same key.

# Fix: use method signature (name+param types) as key instead of just name
old2 = '        val methods = LinkedHashMap<String, KtMethod>()\n        for (comp in componentList) {\n            for (m in comp.cls.methods.filter { !it.isPrivate && !it.isStatic }) {\n                methods[m.name] = m\n            }\n        }'
new2 = '        val methods = LinkedHashMap<String, KtMethod>()\n        for (comp in componentList) {\n            for (m in comp.cls.methods.filter { !it.isPrivate && !it.isStatic }) {\n                val key = signature(m)\n                if (!methods.containsKey(key)) {\n                    methods[key] = m\n                }\n            }\n        }'
s = s.replace(old2, new2)

# Fix 3: in the methods loop, use the key's name
old3 = '        for (m in methods.values.filter { !(it.returnType.length == 1 && it.returnType[0].isUpperCase()) }) {'
new3 = '        for ((key, m) in methods.filter { !(it.value.returnType.length == 1 && it.value.returnType[0].isUpperCase()) }) {'
s = s.replace(old3, new3)

# Fix 4: add serialize to Entityc interface
# The Entityc interface should have serialize() - it's already there from EntityComp
# But the issue is that `serialize` in the entity class overrides nothing
# because the interface only has serialize() from EntityCompc.
# Wait, the entity has `componentByName["EntityComp"]?.let { base ->...}` which adds Entityc
# And Entityc has serialize() - so it should work.
# 
# Actually the issue is that after the name change (EntityComp -> Entityc via interfaceName),
# the entity adds EntityCompc but EntityCompc doesn't exist anymore - it's Entityc.
# Let me check: interfaceName("EntityComp") = "Entityc". So the supertype is "Entityc" which exists.
# And Entityc has serialize(). So the entity's serialize() override should work.
# 
# But the error says "serialize overrides nothing" - this means the entity is NOT seeing
# serialize() from any superinterface. Let me check the entity's supertypes.
# 
# The entity has: `componentByName["EntityComp"]?.let { base -> typeBuilder.addSuperinterface(...) }`
# This adds Entityc. But `componentByName["EntityComp"]` might not exist because EntityComp
# has @BaseComponent but not @Component, so it's not in the filtered list.

# Ah! EntityComp has @Component and @BaseComponent. Let me check the scanner.
# The scanner checks `it.annotations.containsKey("Component")` - 
# EntityComp has @Component annotation, so it should be found.
# The issue might be that `componentByName` uses the class name (without package) as key.
# EntityComp's name is "EntityComp" (FQN: io.eve.vanilla.comp.EntityComp).
# So `componentByName["EntityComp"]` should work - but it's used as `componentByName["EntityComp"]?.let { base -> ... }`
# in the entity generation. This should work.

# Let me check the actual SimpleEntity.kt to see what supertypes it has
# It has: `SimpleEntity : PosCompc, HealthCompc, IndexableEntity__pos`
# No Entityc! That's the problem. The `componentByName["EntityComp"]` is not working.

# The issue: EntityComp has @Component and @BaseComponent annotations.
# The scanner parses annotations from the source. Let me check if it finds @Component.
# Actually, the scanner might have issues with multiple annotations on the same class.

# Let me check the actual annotation parsing for EntityComp
# EntityComp has: @Component and @BaseComponent
# The scanner's parseAnnotations returns a map of annotation name -> args
# For @Component, it should be "Component" -> {}
# For @BaseComponent, it should be "BaseComponent" -> {}
# So `it.annotations.containsKey("BaseComponent")` should be true.
# But the entityGenerator checks `it.annotations.containsKey("Component")` first.
# And then `ann["base"]?.toBoolean() ?: it.annotations.containsKey("BaseComponent")`
# So the isBase should be true for EntityComp.

# But the issue is entity generation: the entity for UnitDef should add EntityCompc as supertype.
# Let me check: `componentByName["EntityComp"]` - this looks up by simple class name.
# EntityComp is not in the same file as the entity defs, but it's in the same package.
# The scanner collects all classes from all files, so it should be there.

# Actually, looking at the generated code, only PosCompc and HealthCompc are in SimpleEntity's
# supertypes. This means the EntityCompc is NOT being added. Let me check why.

# The code: `componentByName["EntityComp"]?.let { base -> typeBuilder.addSuperinterface(...) }`
# If componentByName["EntityComp"] is null, this is skipped.
# This could happen if the scanner doesn't see EntityComp as a component.

# Let me add a debug print and check

with open(p, 'w') as f:
    f.write(s)

print("Done")