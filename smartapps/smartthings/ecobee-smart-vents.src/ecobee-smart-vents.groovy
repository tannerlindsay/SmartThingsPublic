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
 *	1.0.1 - Initial Release
 *	1.0.2 - Misc optimizations and logging changes
 *	1.0.3 - Correct preferences page naming
 */
def getVersionNum() { return "1.0.3" }
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
	dynamicPage(name: "mainPage", title: "Configure Smart Vents", uninstall: true, install: true) {
    	section(title: "Name for Smart Vents Handler") {
        	label title: "Name this Smart Vents Handler", required: true, defaultValue: "Smart Vents"      
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
            	input(name: "minimumVentLevel", type: "number", title: "Minimum vent level when closed?", required: true, defaultValue:10, description: '10', range: "0..100")
            }
        }
        
		section(title: "Smart Vents: Thermostat") {
			paragraph("Specify which thermostat to monitor for heating/cooling events")
			input(name: "theThermostat", type: "capability.thermostat", title: "Select thermostat", multiple: false, required: true, submitOnChange: true)
		}
		
		section(title: "Target Temperature") {
			input(name: "useThermostat", type: "boolean", title: "Follow temps on theromostat${theThermostat?' '+theThermostat.displayName:''}?", required: true, defaultValue: true, submitOnChange: true)
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
	LOG("installed() entered", 3, "", 'trace')
    initialize()
}

void updated() {
	LOG("updated() entered", 3, "", 'trace')
	unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
	// generateSensorsEvents([doors:'default', windows:'default', vents:'default',SmartRoom:'default'])
}

def initialize() {
	LOG("${getVersionLabel()} Initializing...", 2, "", 'info')
    atomicState.scheduled = false
    // Now, just exit if we are disabled...
	if(tempDisable == true) {
    	LOG("temporarily disabled as per request.", 1, null, "warn")
    	return true
    }

    subscribe(theSensors, 'temperature', changeHandler)	
	subscribe(theThermostat, 'thermostatOperatingState', changeHandler)
    subscribe(theThermostat, 'temperature', changeHandler)
    subscribe(theVents, 'level', changeHandler)
	if (theWindows) subscribe(theWindows, "contact", changeHandler)
    if (useThermostat) {
    	subscribe(theThermostat, 'heatingSetpoint', changeHandler)
        subscribe(theThermostat, 'coolingSetpoint', changeHandler)
    }   
	setTheVents(checkTemperature())
    return true
}

def changeHandler(evt) {
	//if (tempDisable == true) return
    //if (atomicState.scheduled) return
    //atomicState.scheduled = true
	runIn( 2, checkAndSet, [overwrite: true])
    // updateTheVents()
}

def checkAndSet() {
	setTheVents(checkTemperature())
    //atomicState.scheduled = false
}

private String checkTemperature() {
	def cOpState = theThermostat.currentValue('thermostatOperatingState')
    // LOG("Current Operating State ${cOpState}",3,null,'info')
	def cTemp = getCurrentTemperature()
	
	def vents = ''			// if not heating/cooling/fan, then no change to current vents
    def tstatHeatingSetpoint = theThermostat.currentValue('heatingSetpoint') 
    def tstatCoolingSetpoint = theThermostat.currentValue('coolingSetpoint')
	if (useThermostat) {
		if (cOpState.contains('heat')) {
        	vents = (tstatHeatingSetpoint <= cTemp) ? 'closed' : 'open'
            LOG("${theThermostat.displayName} is heating, thermostat setpoint is ${tstatHeatingSetpoint}, room temperature is ${cTemp}",3,null,'info')
        }
		else if (cOpState.contains('cool')) {
        	vents = (tstatCoolingSetpoint >= cTemp) ? 'closed' : 'open'
            LOG("${theThermostat.displayName} is cooling, thermostat setpoint is ${tstatCoolingSetpoint}, room temperature is ${cTemp}",3,null,'info')
        }
	} else {
		if (cOpState.contains('heat')) {
        	vents = (heatingSetpoint <= cTemp) ? 'closed' : 'open'
            LOG("${theThermostat.displayName} is heating, configured setpoint is ${heatingSetpoint}, room temperature is ${cTemp}°",3,null,'info')
        }
		else if (cOpState.contains('cool')) {
        	vents = (coolingSetpoint >= cTemp) ? 'closed' : 'open'
            LOG("${theThermostat.displayName} is cooling, configured setpoint is ${coolingSetpoint}, room temperature is ${cTemp}°",3,null,'info')
        }
	}
	if (cOpState.contains('idle')) {
    	LOG("${theThermostat.displayName} is idle, room temperature is ${cTemp}°",3,null,'info')
    } else if (vents == '' && cOpState.contains('fan only')) {
    	vents = 'open'		// if fan only, open the vents
        LOG("${theThermostat.displayName} is running fan only, room temperature is ${cTemp}°",3,null,'info')
    }
	if (theWindows && theWindows.currentContact.contains('open')) {
		vents = 'closed'	// but if a window is open, close the vents
        LOG("${(theWindows.size()>1)?'A':'The'} window/contact is open",3,null,'info')
    }
	LOG("Vents should be ${vents!=''?vents:'unchanged'}",3,null,'info')
	return vents
}

def getCurrentTemperature() {
	Double tTemp = 0.0
	theSensors.each {
		tTemp += it.currentTemperature
	}
	if (theSensors.size() > 1) tTemp = tTemp / theSensors.size() // average all the sensors, if more than 1
	tTemp = tTemp.round(1)
    return tTemp
}

private def setTheVents(ventState) {
	if (ventState == 'open') {
        allVentsOpen()
    } else if (ventState == 'closed') {
        allVentsClosed()
	}
}

private def updateTheVents() {
	/* if (atomicState.updating)
    	return
    } else {
    	atomicState.updating = true
    }
    */
    
	def theVents = (theEconetVents ? theEconetVents : []) + (theKeenVents ? theKeenVents : [])
    theVents.each {
		if (it.hasCapability('Refresh')) {
    		it.refresh()
    	} else if (it.hasCapability('Polling')) {
    		it.poll()
    	} else if (it.hasCapability('Health Check')) {
    		it.ping()
        }
    }
    // atomicState.updating = false
}

def allVentsOpen() {
	def theVents = (theEconetVents ? theEconetVents : []) + (theKeenVents ? theKeenVents : [])
    //LOG("Opening the vent${theVents.size()>1?'s':''}",3,null,'info')
	theVents?.each { ventOn(it) }
}

def allVentsClosed() {
	def theVents = (theEconetVents ? theEconetVents : []) + (theKeenVents ? theKeenVents : [])
    //LOG("Closing the vent${theVents.size()>1?'s':''}",3,null,'info')
	theVents?.each { ventOff(it) } 
}

private def ventOff( theVent ) {
    if (minimumVentLevel.toInteger() == 0) {
      	if (theVent?.currentSwitch == 'on') {
        	theVent.setLevel(0)
        	theVent.off()
            LOG("Closing ${theVent.displayName}",3,null,'info')
        } else {
        	LOG("${theVent.displayName} is already closed",3,null,'info')
        }
    } else {
    	if (theVent?.currentLevel.toInteger() != minimumVentLevel.toInteger()) {
        	theVent.setLevel(minimumVentLevel.toInteger())	// make sure none of the vents are less than the specified minimum
            LOG("Closing ${theVent.displayName} to ${minimumVentLevel}%",3,null,'info')
        } else {
        	LOG("${theVent.displayName} is already closed",3,null,'info')
        }
    }
}

private def ventOn( theVent ) {
    boolean changed = false
    if (theVent?.currentSwitch == 'off') {
    	theVent.on()
        changed = true
    }
    if (theVent?.currentLevel.toInteger() < 99) {
    	theVent.setLevel(99)
        changed = true
    }
    if (changed) {
    	LOG("Opening ${theVent.displayName}",3,null,'info')
    } else {
    	LOG("${theVent.displayName} is already open",3,null,'info')
    }
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
	log."${logType}" message
	message = "${app.label} ${message}"
	parent?.LOG(message, level, null, logType, event, displayEvent)
}
