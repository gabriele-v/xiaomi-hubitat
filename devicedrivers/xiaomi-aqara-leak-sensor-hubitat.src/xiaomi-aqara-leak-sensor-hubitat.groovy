/**
 *  Xiaomi Aqara Leak Sensor - model SJCGQ11LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.8
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on SmartThings device handler code by a4refillpad
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Code reworked for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *    However, the Aqara Door/Window sensor battery level can be retrieved immediately with a short-press of the reset button.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    To put in pairing mode, with the droplet icon upside-down to view the LED, hold down the center of the top of the sensor.
 *    Release when the LED flashes. After a pause, 3 quick flashes indicates success, while one long flash does not.
 *    If pairing is unsuccessful, start over: hold down the top of the sensor again, and watch for 3 quick flashes.
 *    After 3 quick flashes are seen, keep the sensor "awake" by short-pressing the sensor top repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the above steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the Aqara Motion Sensor should be reconnected and will resume reporting as normal
 *
 */

metadata {
	definition (name: "Aqara Leak Sensor", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Water Sensor"
		capability "Sensor"
		capability "Battery"

		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "String"
		attribute "lastDryEpoch", "String"
		attribute "lastDryTime", "String"
		attribute "lastWetEpoch", "String"
		attribute "lastWetTime", "String"
		attribute "batteryLastReplaced", "String"

		fingerprint profileId: "0104", inClusters: "0000,0003,0001", outClusters: "0019", model: "lumi.sensor_wleak.aq1", deviceJoinName: "Aqara Leak Sensor"

		command "resetBatteryReplacedDate"
		command "resetToDry"
		command "resetToWet"
	}

	preferences {
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
		//Date/Time Stamp Events Config
		input name: "lastCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastCheckin", description: ""
		input name: "otherDateTimeEnable", type: "bool", title: "Enable custom date/time stamp events for lastWet and lastDry", description: ""
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	Map map = [:]
	displayDebugLog("Parsing message: ${description}")
	// Send message data to appropriate parsing function based on the type of report
	if (description?.startsWith('zone status')) {
		// Parse dry / wet status report
		map = parseZoneStatusMessage(Integer.parseInt(description[17]))
	} else {
		def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
		def encoding = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "encoding"}?.split(":")[1].trim(), 16)
		def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
		if (!oldFirmware & valueHex != null & encoding > 0x18 & encoding < 0x3e) {
			displayDebugLog("Data type of payload is little-endian; reversing byte order")
			// Reverse order of bytes in description's payload for LE data types - required for Hubitat firmware 2.0.5 or newer
			valueHex = reverseHexString(valueHex)
		}
		displayDebugLog("Message payload: ${valueHex}")
		if (attrId == "0005") {
			displayDebugLog("Reset button was short-pressed")
			// Parse battery level from longer type of announcement message
			map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
		} else if (attrId == "FF01") {
			// Parse battery level from hourly announcement message
			map = parseBattery(valueHex)
		} else
			displayDebugLog("Unable to parse message")
	}
	if (map != [:]) {
		displayInfoLog(map.descriptionText)
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

// Parse IAS Zone Status message (wet or dry)
private parseZoneStatusMessage(status) {
	def value = status ? "wet" : "dry"
	def timeStampType = status ? "Wet" : "Dry"
	def descText = status ? "Sensor detected water" : "Sensor is dry"
	if (otherDateTimeEnable) {
		sendEvent(name: "last${timeStampType}Epoch", value: now())
		sendEvent(name: "last${timeStampType}Time", value: new Date().toLocaleString())
	}
	return [
		name: 'water',
		value: value,
		descriptionText: descText
	]
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.9
	def maxVolts = voltsmax ? voltsmax : 3.05
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	if (lastCheckinEnable) {
		sendEvent(name: "lastCheckinEpoch", value: now())
		sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
	}
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		descriptionText: descText
	]
	return result
}

// Manually override contact state to dry
def resetToDry() {
	if (device.currentState('water')?.value == "wet") {
		def map = parseZoneStatusMessage(0)
		map.descriptionText = "Manually reset to dry"
		displayInfoLog(map.descriptionText)
		displayDebugLog("Creating event $map")
		sendEvent(map)
	}
}

// Manually override contact state to wet
def resetToWet() {
	if (device.currentState('water')?.value == "dry") {
		def map = parseZoneStatusMessage(1)
		map.descriptionText = "Manually reset to wet"
		displayInfoLog(map.descriptionText)
		displayDebugLog("Creating event $map")
		sendEvent(map)
	}
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayDebugLog("Installing")
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
	return
}

// updated() will run every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
}
