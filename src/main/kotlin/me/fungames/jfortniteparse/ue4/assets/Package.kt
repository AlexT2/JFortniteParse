package me.fungames.jfortniteparse.ue4.assets

import com.github.salomonbrys.kotson.registerTypeAdapter
import com.google.gson.GsonBuilder
import me.fungames.jfortniteparse.exceptions.ParserException
import me.fungames.jfortniteparse.fileprovider.FileProvider
import me.fungames.jfortniteparse.ue4.UClass.Companion.logger
import me.fungames.jfortniteparse.ue4.assets.exports.*
import me.fungames.jfortniteparse.ue4.assets.exports.ItemDefinition
import me.fungames.jfortniteparse.ue4.assets.exports.fort.*
import me.fungames.jfortniteparse.ue4.assets.exports.valorant.*
import me.fungames.jfortniteparse.ue4.assets.objects.FNameEntry
import me.fungames.jfortniteparse.ue4.assets.objects.FObjectExport
import me.fungames.jfortniteparse.ue4.assets.objects.FObjectImport
import me.fungames.jfortniteparse.ue4.assets.objects.FPackageFileSummary
import me.fungames.jfortniteparse.ue4.assets.util.PayloadType
import me.fungames.jfortniteparse.ue4.assets.reader.FAssetArchive
import me.fungames.jfortniteparse.ue4.assets.writer.FAssetArchiveWriter
import me.fungames.jfortniteparse.ue4.assets.writer.FByteArrayArchiveWriter
import me.fungames.jfortniteparse.ue4.locres.Locres
import me.fungames.jfortniteparse.ue4.versions.Ue4Version
import java.io.File
import java.io.OutputStream

@ExperimentalUnsignedTypes
class Package(uasset : ByteArray, uexp : ByteArray, ubulk : ByteArray? = null, name : String, provider: FileProvider? = null, var game : Ue4Version = Ue4Version.GAME_UE4_LATEST) {

    companion object {
        val packageMagic = 0x9E2A83C1u
        val gson = GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(JsonSerializer.packageConverter)
            .registerTypeAdapter(JsonSerializer.importSerializer)
            .registerTypeAdapter(JsonSerializer.exportSerializer)
            .registerTypeAdapter(JsonSerializer.uobjectSerializer)
            .create()
    }

    constructor(uasset : File, uexp : File, ubulk : File?) : this(uasset.readBytes(), uexp.readBytes(),
        ubulk?.readBytes(), uasset.nameWithoutExtension)

    private val uassetAr = FAssetArchive(uasset, provider)
    private val uexpAr = FAssetArchive(uexp, provider)
    private val ubulkAr = if (ubulk != null) FAssetArchive(ubulk, provider) else null

    val info : FPackageFileSummary
    val nameMap : MutableList<FNameEntry>
    val importMap : MutableList<FObjectImport>
    val exportMap : MutableList<FObjectExport>

    val exports = mutableListOf<UExport>()

    init {
        uassetAr.game = game.versionInt
        uexpAr.game = game.versionInt
        ubulkAr?.game = game.versionInt
        info = FPackageFileSummary(uassetAr)
        if (info.tag != packageMagic)
            throw ParserException("Invalid uasset magic, ${info.tag} != $packageMagic")


        uassetAr.seek(this.info.nameOffset)
        nameMap = mutableListOf()
        uassetAr.nameMap = nameMap
        for (i in 0 until info.nameCount)
            nameMap.add(FNameEntry(uassetAr))


        uassetAr.seek(this.info.importOffset)
        importMap = mutableListOf()
        uassetAr.importMap = importMap
        for (i in 0 until info.importCount)
            importMap.add(FObjectImport(uassetAr))

        uassetAr.seek(this.info.exportOffset)
        exportMap = mutableListOf()
        uassetAr.exportMap = exportMap
        for (i in 0 until info.exportCount)
            exportMap.add(FObjectExport(uassetAr))

        //Setup uexp reader
        uexpAr.nameMap = nameMap
        uexpAr.importMap = importMap
        uexpAr.exportMap = exportMap
        uexpAr.uassetSize = info.totalHeaderSize
        uexpAr.info = info

        //If attached also setup the ubulk reader
        if (ubulkAr != null) {
            ubulkAr.uassetSize = info.totalHeaderSize
            ubulkAr.uexpSize = exportMap.sumBy { it.serialSize.toInt() }
            ubulkAr.info = info
            uexpAr.addPayload(PayloadType.UBULK, ubulkAr)
        }

        exportMap.forEach {
            val exportType = it.classIndex.importName.substringAfter("Default__")
            uexpAr.seekRelative(it.serialOffset.toInt())
            val validPos = uexpAr.pos() + it.serialSize
            when(exportType) {
                "BlueprintGeneratedClass" -> {
                    val className = it.templateIndex.importObject?.className?.text
                    if (className != null)
                        readExport(className, it)
                    else {
                        logger.warn { "Couldn't find content class of BlueprintGeneratedClass, attempting normal UObject deserialization" }
                        readExport(exportType, it)
                    }
                }
                else -> readExport(exportType, it)
            }
            if (validPos != uexpAr.pos().toLong())
                logger.warn("Did not read $exportType correctly, ${validPos - uexpAr.pos()} bytes remaining")
            else
                logger.debug("Successfully read $exportType at ${uexpAr.toNormalPos(it.serialOffset.toInt())} with size ${it.serialSize}")
        }
        matchValorantCharacterAbilities()
        uassetAr.clearImportCache()
        uexpAr.clearImportCache()
        ubulkAr?.clearImportCache()
        logger.info("Successfully parsed package : $name")
    }

    fun readExport(exportType : String, it : FObjectExport) {
        when (exportType) {
            //UE generic export classes
            "Texture2D" -> exports.add(UTexture2D(uexpAr, it))
            "SoundWave" -> exports.add(USoundWave(uexpAr, it))
            "DataTable" -> exports.add(UDataTable(uexpAr, it))
            "CurveTable" -> exports.add(UCurveTable(uexpAr, it))
            "StringTable" -> exports.add(UStringTable(uexpAr, it))
            //Valorant specific classes
            "CharacterUIData" -> exports.add(CharacterUIData(uexpAr, it))
            "CharacterAbilityUIData" -> exports.add(CharacterAbilityUIData(uexpAr, it))
            "BaseCharacterPrimaryDataAsset_C", "CharacterDataAsset" -> exports.add(CharacterDataAsset(uexpAr, it))
            "CharacterRoleDataAsset" -> exports.add(CharacterRoleDataAsset(uexpAr, it))
            "CharacterRoleUIData" -> exports.add(CharacterRoleUIData(uexpAr, it))
            //Fortnite Specific Classes
            "FortMtxOfferData" -> exports.add(FortMtxOfferData(uexpAr, it))
            "FortItemCategory" -> exports.add(FortItemCategory(uexpAr, it))
            "CatalogMessaging" -> exports.add(FortCatalogMessaging(uexpAr, it))
            "FortItemSeriesDefinition" -> exports.add(FortItemSeriesDefinition(uexpAr, it))
            "AthenaItemWrapDefinition", "FortBannerTokenType",
            "FortVariantTokenType", "FortHeroType",
            "FortTokenType", "FortWorkerType",
            "FortDailyRewardScheduleTokenDefinition",
            "FortAbilityKit" -> exports.add(ItemDefinition(uexpAr, it))
            else -> {
                if (exportType.contains("ItemDefinition")) {
                    exports.add(ItemDefinition(uexpAr, it)
                    )
                } else if (exportType.startsWith("FortCosmetic") && exportType.endsWith("Variant")) {
                    val variant = FortCosmeticVariant(uexpAr, it)
                    matchFortniteItemDefAndVariant(variant)
                    exports.add(variant)
                } else
                    exports.add(
                        UObject(
                            uexpAr,
                            it
                        )
                    )

            }
        }
    }

    private fun matchFortniteItemDefAndVariant(variant: FortCosmeticVariant) = getExportOfTypeOrNull<ItemDefinition>()?.variants?.add(variant)

    private fun matchValorantCharacterAbilities() {
        val uiData = getExportOfTypeOrNull<CharacterUIData>() ?: return
        uiData.abilitiesWithIndex.forEach { slot, i ->
            val export = exports.getOrNull(i - 1)
            if (export != null && export is CharacterAbilityUIData)
                uiData.abilities[slot] = export
        }
    }

    /**
     * @return the first export of the given type
     * @throws IllegalArgumentException if there is no export of the given type
     */
    @Throws(IllegalArgumentException::class)
    inline fun <reified T : UExport> getExportOfType() = getExportsOfType<T>().first()

    /**
     * @return the first export of the given type or null if there is no
     */
    inline fun <reified T : UExport> getExportOfTypeOrNull() = getExportsOfType<T>().firstOrNull()

    /**
     * @return the all exports of the given type
     */
    inline fun <reified T : UExport> getExportsOfType() = exports.filterIsInstance<T>()

    fun applyLocres(locres : Locres?) {
        exports.forEach { it.applyLocres(locres) }
    }

    fun toJson() = gson.toJson(this)!!

    //Not really efficient because the uasset gets serialized twice but this is the only way to calculate the new header size
    private fun updateHeader() {
        val uassetWriter = FByteArrayArchiveWriter()
        uassetWriter.game = game.versionInt
        uassetWriter.nameMap = nameMap
        uassetWriter.importMap = importMap
        uassetWriter.exportMap = exportMap
        info.serialize(uassetWriter)
        val nameMapOffset = uassetWriter.pos()
        if(info.nameCount != nameMap.size)
            throw ParserException("Invalid name count, summary says ${info.nameCount} names but name map is ${nameMap.size} entries long")
        nameMap.forEach { it.serialize(uassetWriter) }
        val importMapOffset = uassetWriter.pos()
        if(info.importCount != importMap.size)
            throw ParserException("Invalid import count, summary says ${info.importCount} imports but import map is ${importMap.size} entries long")
        importMap.forEach { it.serialize(uassetWriter) }
        val exportMapOffset = uassetWriter.pos()
        if(info.exportCount != exportMap.size)
            throw ParserException("Invalid export count, summary says ${info.exportCount} exports but export map is ${exportMap.size} entries long")
        exportMap.forEach { it.serialize(uassetWriter) }
        info.totalHeaderSize = uassetWriter.pos()
        info.nameOffset = nameMapOffset
        info.importOffset = importMapOffset
        info.exportOffset = exportMapOffset
    }

    fun write(uassetOutputStream: OutputStream, uexpOutputStream: OutputStream, ubulkOutputStream: OutputStream?) {
        updateHeader()
        val uexpWriter = writer(uexpOutputStream)
        uexpWriter.game = game.versionInt
        uexpWriter.uassetSize = info.totalHeaderSize
        exports.forEach {
            val beginPos = uexpWriter.relativePos()
            it.serialize(uexpWriter)
            val finalPos = uexpWriter.relativePos()
            it.export?.serialOffset = beginPos.toLong()
            it.export?.serialSize = (finalPos - beginPos).toLong()
        }
        uexpWriter.writeUInt32(packageMagic)
        val uassetWriter = writer(uassetOutputStream)
        uassetWriter.game = game.versionInt
        info.serialize(uassetWriter)
        val nameMapPadding = info.nameOffset - uassetWriter.pos()
        if(nameMapPadding > 0)
            uassetWriter.write(ByteArray(nameMapPadding))
        if(info.nameCount != nameMap.size)
            throw ParserException("Invalid name count, summary says ${info.nameCount} names but name map is ${nameMap.size} entries long")
        nameMap.forEach { it.serialize(uassetWriter) }

        val importMapPadding = info.importOffset - uassetWriter.pos()
        if(importMapPadding > 0)
            uassetWriter.write(ByteArray(importMapPadding))
        if(info.importCount != importMap.size)
            throw ParserException("Invalid import count, summary says ${info.importCount} imports but import map is ${importMap.size} entries long")
        importMap.forEach { it.serialize(uassetWriter) }

        val exportMapPadding = info.exportOffset - uassetWriter.pos()
        if(exportMapPadding > 0)
            uassetWriter.write(ByteArray(exportMapPadding))
        if(info.exportCount != exportMap.size)
            throw ParserException("Invalid export count, summary says ${info.exportCount} exports but export map is ${exportMap.size} entries long")
        exportMap.forEach { it.serialize(uassetWriter) }
        ubulkOutputStream?.close()
    }

    fun write(uasset: File, uexp: File, ubulk: File?) {
        val uassetOut = uasset.outputStream()
        val uexpOut = uexp.outputStream()
        val ubulkOut = if(this.ubulkAr != null) ubulk?.outputStream() else null
        write(uassetOut, uexpOut, ubulkOut)
        uassetOut.close()
        uexpOut.close()
        ubulkOut?.close()
    }

    fun writer(outputStream: OutputStream) = FAssetArchiveWriter(
        outputStream
    ).apply {
        nameMap = this@Package.nameMap
        importMap = this@Package.importMap
        exportMap = this@Package.exportMap
    }
}