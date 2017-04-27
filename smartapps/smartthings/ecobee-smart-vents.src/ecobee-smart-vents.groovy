/**
 *  ecobee Smart Vents
 *
 *  Copyright 2017 Barry A. Burke

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
 *	1.0.0 - Initial Release
 */
def getVersionNum() { return "1.0.0" }
private def getVersionLabel() { return "ecobee Smart Vents Version ${getVersionNum()}" }
import groovy.json.JsonSlurper

definition(
	name: "ecobee Smart Vents",
	namespace: "smartthings",
	author: "Barry A. Burke (storageanarchy at gmail dot com)",
	description: "Automates SmartThings-controlled vents to meet a target temperature in a room",
	category: "Convenience",
	parent: "smartthings:Ecobee (Connect)",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/ecobee.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/ecobee@2x.png",
	singleInstance: false
)

preferences {
	page(name: "mainPage")
}

// Preferences Pages
def mainPage() {
	dynamicPage(name: "mainPage", title: "Configure Smart Room", uninstall: true, install: true) {
    	section(title: "Name for Smart Room Handler") {
        	label title: "Name this Smart Room Handler", required: true, defaultValue: "Smart Vents"      
        }
        
        section(title: "Smart Vents Temperature Sensor(s)") {
        	if (settings.tempDisable == true) {
            	paragraph "WARNING: Temporarily Disabled as requested. Turn back on to Enable handler."
            } else {
            	paragraph("Select temperature sensors for this handler. If you select multiple sensors, the temperature will be averaged across all of them.")
        		input(name: "theSensors", type:"capability.temperatureMeasurement", title: "Use which temperature Sensor(s)", required: true, multiple: true, submitOnChange: true)
            }
		}
        
       	section(title: "Smart Vents Windows (optional)") {
        	paragraph("Windows will temporarily deactivate Smart Vents while they are open")
            input(name: "theWindows", type: "capability.contactSensor", title: "Which Window contact sensor(s)? (optional)", required: false, multiple: true)
        }
       
        section(title: "Smart Vents") {
        	paragraph("Specified Econet or Keen vents will be opened until target temperature is achieved, and then closed")
            input(name: "theEconetVents", type: "device.econetVent", title: "Control which EcoNet Vent(s)?", required: false, multiple: true, submitOnChange: true)
            input(name: "theKeenVents", type: "device.keenHomeSmartVent", title: "Control which Keen Home Smart Vent(s)?", required: false, multiple:true, submitOnChange: true)
            if (theEconetVents || theKeenVents) {
            	paragraph("Fully closing too many vents at once may be detrimental to your HVAC system. You may want to define a minimum closed percentage")
            	input(name: "minimumVentLevel", type: "number", title: "Minimum vent level when closed?", required: true, defaultValue:0, description: '0', range: "0..100")
            }
        }
        
		section(title: "Smart Vents: Thermostat") {
			paragraph("Specify which thermostat to monitor for heating/cooling events")
			input(name: "theThermostat", type: "capability.thermostat", title: "Select thermostat", multiple: false, required: true, submitOnChange: true)
		}
		
		section(title: "Target Temperature") {
			input(name: "useThermostat", type: "boolean", title: "Follow temps on theromostat${theThermostat?' '+theThermostat.name:''}?", required: true, defaultValue: true, submitOnChange: true)
			if (!useThermostat) {
				input(name: "heatingSetpoint", type: "number", title: "Target heating setpoint?", required: true)
				input(name: "coolingSetpoint", type: "number", title: "Target cooling setpoint?", required: true)
			}
		}
        	
		section(title: "Temporarily Disable?") {
        	input(name: "tempDisable", title: "Temporarily Disable this Handler? ", type: "bool", required: false, description: "", submitOnChange: true)                
        }
        
        section (getVersionLabel())
    }
}

// Main functions
void installed() {
	LOG("installed() entered", 2, "", 'trace')
}

void updated() {
	LOG("updated() entered", 2, "", 'trace')
	unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
	// generateSensorsEvents([doors:'default', windows:'default', vents:'default',SmartRoom:'default'])
}

def initialize() {
	LOG("${getVersionLabel()} Initializing...", 3, "", 'info')
    
    // Now, just exit if we are disabled...
	if(tempDisable == true) {
    	LOG("temporarily disabled as per request.", 2, null, "warn")
    	return true
    }
	
    subscribe(theSensors, 'temperature', changeHandler)	
	subscribe(theThermostat, 'thermostatOperatingState', changeHandler)
	if (theWindows) subscribe(theWindows, "contact", changeHandler)
    if (useThermostat) {
    	subscribe(theThermostat, 'heatingSetpoint', changeHandler)
        subscribe(theThermostat, 'coolingSetpoint', changeHandler)
    }   
	setTheVents(checkTemperature())
    return true
}

private def ventOff( theVent ) {
    if (minimumVentLevel.toInteger() == 0) {
      	if (theVent?.currentSwitch == 'on') theVent.off()
    } else {
    	if (theVent?.currentLevel.toInteger() != minimumVentLevel.toInteger()) theVent.setLevel(minimumVentLevel.toInteger())	// make sure none of the vents are less than the specified minimum
    }
}

private def ventOn( theVent ) {
    if (theVent?.currentSwitch == 'off') theVent.on()
    if (theVent?.currentLevel.toInteger() < 99) theVent.setLevel(99)
}

def changeHandler(evt) {
	setTheVents(checkTemperature())
}

private String checkTemperature() {
	def cOpState = theThermostat.currentValue('thermostatOperatingState')
    LOG("Current Operating State ${cOpState}",2,null,'info')
	def cTemp = getCurrentTemperature()
	
	def vents = ''			// if not heating/cooling/fan, then no change to current vents
	if (useThermostat) {
		if (cOpState.contains('heat')) vents = (theThermostat.currentValue('heatingSetpoint') <= cTemp) ? 'closed' : 'open'
		else if (cOpState.contains('cool')) vents = (theThermostat.currentValue('coolingSetpoint') >= cTemp) ? 'closed' : 'open'
	} else {
		if (cOpState.contains('heat')) vents = (heatingSetpoint <= cTemp) ? 'closed' : 'open'
		else if (cOpState.contains('cool')) vents = (coolingSetpoint >= cTemp) ? 'closed' : 'open'
	}
    
	if (vents == '' && cOpState.contains('fan only')) vents = 'open'		// if fan only, open the vents
	if (theWindows && theWindows.currentContact.contains('open')) vents = 'closed'	// but if a window is open, close the vents
	LOG("Vents should be ${vents!=''?vents:'unchanged'}",2,null,'info')
	return vents
}

private def setTheVents(ventState) {
	def theVents = (theEconetVents ? theEconetVents : []) 
    theVents += (theKeenVents ? theKeenVents : [])
	
	theVents.each {
		if (ventState == 'open') {
			ventOn(it)
		} else if (ventState == 'closed') {
			ventOff(it)
		}
	}
}

def getCurrentTemperature() {
	Double tTemp = 0.0
	theSensors.each {
		tTemp += it.currentTemperature
	}
	if (theSensors.size() > 1) tTemp = tTemp / theSensors.size()

    LOG("Current temperature is ${tTemp}",2,null,'info')
    return tTemp
}

// Ask our parents for help sending the events to our peer sensor devices
private def generateSensorsEvents( Map dataMap ) {
	LOG("generating ${dataMap} events for ${theSensors}",3,null,'info')
	theSensors.each { DNI ->
        parent.getChildDevice(DNI)?.generateEvent(dataMap)
    }
}

// Helper Functions
private def LOG(message, level=3, child=null, logType="debug", event=true, displayEvent=true) {
	message = "${app.label} ${message}"
	parent?.LOG(message, level, null, logType, event, displayEvent)
    log."${logType}" message
}
