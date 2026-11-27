package com.andrerinas.openheadunit.aiways

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.google.protobuf.UnknownFieldSet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bytes the phone gets: the fields our generated classes lack are written as unknown fields,
 * so these tests read the encoding back by field number, as the phone does.
 */
class AiwaysClusterServicesTest {

    private fun fields(bytes: ByteArray): UnknownFieldSet = UnknownFieldSet.parseFrom(bytes)

    private fun varint(set: UnknownFieldSet, number: Int): Long = set.getField(number).varintList.single()

    private fun nested(set: UnknownFieldSet, number: Int): UnknownFieldSet =
        UnknownFieldSet.parseFrom(set.getField(number).lengthDelimitedList.single())

    @Test
    fun `the video sink is display 1 of type CLUSTER on channel 14`() {
        val service = fields(AiwaysClusterTest.videoService(AiwaysClusterLadder.RUNGS[0]).toByteArray())
        assertEquals(14L, varint(service, 1)) // Service.id
        val sink = nested(service, 3) // Service.media_sink_service
        assertEquals(1L, varint(sink, 6)) // MediaSinkService.display_id
        assertEquals(1L, varint(sink, 7)) // MediaSinkService.display_type = DISPLAY_TYPE_CLUSTER
        assertEquals(3L, varint(sink, 1)) // available_type = MEDIA_CODEC_VIDEO_H264_BP
    }

    @Test
    fun `the video configuration carries the rung's size, margins and density`() {
        val fit = AiwaysClusterLadder.RUNGS[1]
        val service = fields(AiwaysClusterTest.videoService(fit).toByteArray())
        val config = nested(nested(service, 3), 4) // MediaSinkService.video_configs
        assertEquals(1L, varint(config, 1)) // codec_resolution = 800x480
        assertEquals(2L, varint(config, 2)) // frame_rate = 30
        assertEquals(530L, varint(config, 3)) // margin_width
        assertEquals(0L, varint(config, 4)) // margin_height
        assertEquals(160L, varint(config, 5)) // density
        assertEquals(3L, varint(config, 10)) // video_codec_type = H264_BP
    }

    @Test
    fun `the input service is bound to display 1 on channel 15`() {
        val service = fields(AiwaysClusterTest.inputService().toByteArray())
        assertEquals(15L, varint(service, 1))
        val input = nested(service, 4) // Service.input_source_service
        assertEquals(1L, varint(input, 5)) // InputSourceService.display_id
    }

    @Test
    fun `the services survive inside a full discovery response`() {
        val response = Control.ServiceDiscoveryResponse.newBuilder()
            .setMake("Aiways").setModel("U5").setYear("2021").setVehicleId("x")
            .setHeadUnitModel("m").setHeadUnitMake("a").setHeadUnitSoftwareBuild("1")
            .setHeadUnitSoftwareVersion("1").setDriverPosition(Control.DriverPosition.DRIVER_POSITION_LEFT)
            .setCanPlayNativeMediaDuringVr(false)
            .addServices(AiwaysClusterTest.videoService(AiwaysClusterLadder.RUNGS[0]))
            .addServices(AiwaysClusterTest.inputService())
            .build()
        val parsed = Control.ServiceDiscoveryResponse.parseFrom(response.toByteArray())
        val sink = parsed.servicesList.single { it.id == Channel.ID_CLUSTER_VID }.mediaSinkService
        assertEquals(1L, sink.unknownFields.getField(7).varintList.single())
    }
}
