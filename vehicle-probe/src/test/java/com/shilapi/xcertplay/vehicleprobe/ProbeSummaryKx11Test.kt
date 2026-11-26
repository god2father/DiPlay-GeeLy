package com.shilapi.xcertplay.vehicleprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSummaryKx11Test {

    private val kx11Report = """
        DiPlay 0.2.22.5 (44) · vehicle property scan report
        Report type: vehicle_properties
        Vehicle model: 星越 L · KX11（25 揽星）
        Head unit: QTI KX11_A2

        --- Vehicle property scan ---
        schema=geely_property_probe_v2
        chain,kind,area,name,propertyId,hexId,encoding,value,state,unit,scale,offset,origin,reserved
        "adapt_api","sensor","global","SENSOR_TYPE_ENDURANCE_MILEAGE","1050624","0x100800","LINEAR","212.0","READ_OK","km","1.0","0.0","sensor-scan",""
        "adapt_api","sensor","global","SENSOR_TYPE_TEMPERATURE_AMBIENT","1051392","0x100B00","LINEAR","16.8","READ_OK","°C","1.0","0.0","sensor-scan",""
        "adapt_api","sensor","global","SENSOR_TYPE_TEMPERATURE_INDOOR","1051648","0x100C00","LINEAR","18.4","READ_OK","°C","1.0","0.0","sensor-scan",""
        "diagnostic","channel","0","car","0","none","NONE","","READY","","1.0","0.0","com.ecarx.xui.adaptapi.car.CarImpl",""
        "gd_vehicle_bridge","sensor","global","SENSOR_TYPE_CAR_SPEED","1048832","0x100100","LINEAR","42.0","READ_OK","km/h","1.0","0.0","bridge-scan",""
    """.trimIndent()

    @Test
    fun parseCountsAdaptApiAndGdBridgeRowsWithoutLegacyHeader() {
        val summary = ProbeSummary.parse(kx11Report)
        assertEquals(4, summary.total)
        assertEquals(4, summary.readable)
        assertEquals(0, summary.invalid)
        assertEquals(0, summary.unavailable)
    }

    @Test
    fun reportImporterAcceptsAdaptApiAndGdVehicleBridgeRows() {
        val bindings = ReportPropertyImporter.extract(kx11Report)
        val fields = bindings.map { it.field }.toSet()
        assertTrue(VehicleField.RANGE in fields)
        assertTrue(VehicleField.AMBIENT_TEMPERATURE in fields)
        assertTrue(VehicleField.CABIN_TEMPERATURE in fields)
        assertTrue(VehicleField.SPEED in fields)
    }
}
