package fi.evident.apina.spring

import fi.evident.apina.java.model.JavaAnnotation
import fi.evident.apina.java.model.JavaMethod
import fi.evident.apina.java.model.JavaModel
import fi.evident.apina.java.model.type.JavaType

/**
 * Wrapper for annotations that performs Spring-specific lookup for attributes.
 */
class SpringAnnotation(
        /** The original annotation type that caller was interested in */
        private val annotationType: JavaType.Basic,
        /** The set of annotations that were actually implied by site, ordered by specificity */
        private val annotations: Collection<JavaAnnotation>,
        private val javaModel: JavaModel) {

    inline fun <reified T : Any> getAttribute(attributeName: String): T? = getAttribute(attributeName, T::class.java)
    inline fun <reified T : Any> getUniqueAttributeValue(attributeName: String): T? = getUniqueAttributeValue(attributeName, T::class.java)

    /**
     * Tries to find value for given attribute, considering meta-annotations and `@AliasFor`.
     */
    fun <T : Any> getAttribute(attributeName: String, type: Class<T>): T? =
            annotations.asSequence()
                    .mapNotNull { getAttributeFrom(it, attributeName, type) }
                    .firstOrNull()

    fun <T> getUniqueAttributeValue(attributeName: String, type: Class<T>): T? {
        val value = getAttribute<Any>(attributeName)

        return if (value is Array<*>) {
            when (value.size) {
                0 -> null
                1 -> type.cast(value[0])
                else -> throw IllegalArgumentException("multiple values for $attributeName in $this")
            }
        } else {
            type.cast(value)
        }
    }

    private fun <T : Any> getAttributeFrom(annotation: JavaAnnotation, attributeName: String, type: Class<T>): T? {
        if (annotationType == annotation.name) {
            val value = annotation.getAttribute(attributeName, type)
            if (value != null)
                return value
        }

        return findAliases(annotation.name)
                .asSequence()
                .filter { it.matches(annotationType, attributeName) }
                .mapNotNull { annotation.getAttribute(it.sourceAttribute, type) }
                .firstOrNull()
    }

    /**
     * Returns all aliases defined by given annotation type.
     */
    private fun findAliases(annotationType: JavaType.Basic): Collection<AliasFor> {
        val clazz = javaModel.findClass(annotationType)
        if (clazz != null) {
            return clazz.methods
                    .asSequence()
                    .filter { it.hasAnnotation(AliasFor.TYPE) }
                    .map { AliasFor(annotationType, it.name, it.findAliasTargets()) }
                    .toList()

        } else {
            return emptyList()
        }
    }

    private fun JavaMethod.findAliasTargets(): Set<Pair<JavaType.Basic, String>> {
        val result = mutableSetOf<Pair<JavaType.Basic, String>>()

        fun recurse(attribute: JavaMethod) {
            val aliasFor = attribute.findAnnotation(AliasFor.TYPE)
            if (aliasFor != null) {
                val targetType = aliasFor.getAttribute<JavaType.Basic>("annotation") ?: attribute.owningClass.type.toBasicType()
                val targetAttribute = aliasFor.getAttribute<String>("attribute") ?: aliasFor.getAttribute<String>("value") ?: attribute.name

                if (result.add(targetType to targetAttribute)) {
                    val clazz = javaModel.findClass(targetType)
                    if (clazz != null) {
                        val target = clazz.methods.find { it.name == targetAttribute && it.hasAnnotation(AliasFor.TYPE)}
                        if (target != null)
                            recurse(target)
                    }
                }
            }
        }

        recurse(this)
        return result
    }
}
