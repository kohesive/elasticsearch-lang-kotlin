package uy.kohesive.elasticsearch.kotlinscript.common

import uy.kohesive.chillamda.Chillambda
import uy.kohesive.cuarentena.Cuarentena
import uy.kohesive.cuarentena.Cuarentena.Companion.painlessPlusKotlinPolicy
import uy.kohesive.cuarentena.policy.AccessTypes
import uy.kohesive.cuarentena.policy.PolicyAllowance
import uy.kohesive.cuarentena.policy.toPolicy
import java.lang.reflect.Type

object KotlinScriptConfiguredChillambda {
    val sharedReceiverCuarentenaPolicies = listOf(
            PolicyAllowance.ClassLevel.ClassAccess(EsKotlinScriptTemplate::class.java.canonicalName, setOf(AccessTypes.ref_Class_Instance)),
            PolicyAllowance.ClassLevel.ClassMethodAccess(EsKotlinScriptTemplate::class.java.canonicalName, "*", "*", setOf(AccessTypes.call_Class_Instance_Method)),
            PolicyAllowance.ClassLevel.ClassPropertyAccess(EsKotlinScriptTemplate::class.java.canonicalName, "*", "*", setOf(AccessTypes.read_Class_Instance_Property)),
            // These references only allow passing along a Type or Class but not doing anything with them
            PolicyAllowance.ClassLevel.ClassAccess(Type::class.java.canonicalName, setOf(AccessTypes.ref_Class_Instance)),
            PolicyAllowance.ClassLevel.ClassAccess(Class::class.java.canonicalName, setOf(AccessTypes.ref_Class))
    )

    val receiverCuarentenaPolicies = sharedReceiverCuarentenaPolicies.toPolicy().toSet()

    val scriptTemplateCuarentenaPolicies = (sharedReceiverCuarentenaPolicies + listOf(
            PolicyAllowance.ClassLevel.ClassConstructorAccess(EsKotlinScriptTemplate::class.java.canonicalName, "*", setOf(AccessTypes.call_Class_Constructor))
    )).toPolicy().toSet()

    val cuarentena = Cuarentena(painlessPlusKotlinPolicy + receiverCuarentenaPolicies)
    val chillambda = Chillambda(cuarentena)

}