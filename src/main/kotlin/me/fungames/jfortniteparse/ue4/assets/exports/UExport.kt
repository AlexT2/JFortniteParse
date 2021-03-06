package me.fungames.jfortniteparse.ue4.assets.exports

import me.fungames.jfortniteparse.ue4.UClass
import me.fungames.jfortniteparse.ue4.assets.objects.FObjectExport
import me.fungames.jfortniteparse.ue4.assets.writer.FAssetArchiveWriter
import me.fungames.jfortniteparse.ue4.locres.Locres

@ExperimentalUnsignedTypes
abstract class UExport(val exportType: String) : UClass() {
    abstract var baseObject : UObject
    abstract fun serialize(Ar: FAssetArchiveWriter)
    var export : FObjectExport? = null

    constructor(exportObject : FObjectExport) : this(exportObject.classIndex.importName) {
        export = exportObject
    }

    open fun applyLocres(locres : Locres?) {}
}