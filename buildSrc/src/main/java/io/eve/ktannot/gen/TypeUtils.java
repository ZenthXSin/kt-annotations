package io.eve.ktannot.gen;

import com.squareup.kotlinpoet.ClassName;
import com.squareup.kotlinpoet.ParameterizedTypeName;
import com.squareup.kotlinpoet.TypeName;
import com.squareup.kotlinpoet.TypeNames;

/**
 * Java helper to create ParameterizedTypeName instances.
 * KotlinPoet's ParameterizedTypeName.get() is @PublishedApi internal in Kotlin,
 * but from Java all methods are public.
 */
public class TypeUtils {
    public static ParameterizedTypeName parameterizedType(ClassName rawType, TypeName... typeArgs) {
        return ParameterizedTypeName.get(rawType, typeArgs);
    }

    public static ParameterizedTypeName quadTreeStar() {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("arc.math.geom.QuadTree"),
            TypeNames.STAR
        );
    }

    public static ParameterizedTypeName seqStar() {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("arc.struct.Seq"),
            TypeNames.STAR
        );
    }

    public static ParameterizedTypeName arrayStar() {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("kotlin.Array"),
            TypeNames.STAR
        );
    }

    public static ParameterizedTypeName entityGroupOf(ClassName entityType) {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("mindustry.entities.EntityGroup"),
            entityType
        );
    }

    public static ParameterizedTypeName seqOf(ClassName elementType) {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("arc.struct.Seq"),
            elementType
        );
    }

    public static TypeName arrayOfProv() {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("kotlin.Array"),
            provType()
        );
    }

    public static TypeName provType() {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("arc.func.Prov"),
            ClassName.bestGuess("mindustry.gen.Entityc")
        );
    }

    public static ParameterizedTypeName objectMap() {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("arc.struct.ObjectMap"),
            ClassName.bestGuess("java.lang.String"),
            ClassName.bestGuess("arc.func.Prov"),
            TypeNames.STAR, TypeNames.STAR
        );
    }

    public static ParameterizedTypeName intMap() {
        return ParameterizedTypeName.get(
            ClassName.bestGuess("arc.struct.IntMap"),
            ClassName.bestGuess("java.lang.String")
        );
    }

    /** 解析类型字符串的顶层泛型参数(支持嵌套,如 ThreadLocal<Seq<Transform>> → [Seq<Transform>])。 */
    public static java.util.List<String> parseGenericArgs(String type) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int gi = type.indexOf('<');
        if (gi < 0) return parts;
        int depth = 0;
        StringBuilder buf = new StringBuilder();
        for (int i = gi + 1; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') depth++;
            if (c == '>') {
                if (depth == 0) break;
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(buf.toString().trim());
                buf.setLength(0);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) parts.add(buf.toString().trim());
        return parts;
    }
}
