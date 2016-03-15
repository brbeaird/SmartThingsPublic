/**
 *	RainMachine Service Manager SmartApp
 * 
 *  Author: Jason Mok/Brian Beaird
 *  Date: 2016-3-15
 *
 ***************************
 *
 *  Copyright 2014 Jason Mok
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
 **************************
 *
 * REQUIREMENTS
 * 1) This only works for firmware version 3.63 on RainMachine
 * 2) You know your external IP address
 * 3) You have forwarded port 80 (Currently does not work with SSL 443/18443, this is smartthings limitation). 
 * 4) You must have all scripts installed 
 *
 **************************
 * 
 * USAGE
 * 1) Put this in SmartApp. Don't install until you have all other device types scripts added
 * 2) Configure the first page which collects your ip address & port and password to log in to RainMachine
 * 3) For each items you pick on the Programs/Zones page, it will create a device
 * 4) Enjoy!
 *
 */
definition(
	name: "RainMachine",
	namespace: "copy-ninja",
	author: "Jason Mok",
	description: "Connect your RainMachine to control your irrigation",
	category: "SmartThings Labs",
	iconUrl:   "http://smartthings.copyninja.net/icons/RainMachine@1x.png",
	iconX2Url: "http://smartthings.copyninja.net/icons/RainMachine@2x.png",
	iconX3Url: "http://smartthings.copyninja.net/icons/RainMachine@3x.png"
)

preferences {	
    page(name: "prefLogIn", title: "RainMachine")    
    page(name: "prefLogInWait", title: "RainMachine")    
    page(name: "prefListProgramsZones", title: "RainMachine")  
    
}

/* Preferences */
def prefLogIn() {
	
    //RESET ALL THE THINGS
    atomicState.initialLogin = false
    atomicState.loginResponse = null    
    atomicState.zonesResponse = null
    atomicState.programsResponse = null    
    
    def showUninstall = ip_address != null && password != null
	return dynamicPage(name: "prefLogIn", title: "Connect to RainMachine", nextPage:"prefLogInWait", uninstall:showUninstall, install: false) {
		section("Server Information"){
			input("ip_address", "text", title: "IP Address/Host Name", description: "IP Address/Host Name of RainMachine")			
		}
		section("Login Credentials"){
			input("password", "password", title: "Password", description: "RainMachine password")
		}   
        section("Server Polling"){
			input("polling", "int", title: "Polling Interval (in minutes)", description: "in minutes", defaultValue: 5)
		}
	}
}

def prefLogInWait() {
    log.debug "Logging in...waiting..." + "Current login response: " + atomicState.loginResponse
    
    doLogin()
    
    //Wait up to 20 seconds for login response
    def i  = 0   
    while (i < 5){
    	pause(2000)
        if (atomicState.loginResponse != null){
        	log.debug "Got a response! Let's go!"
            i = 5
        }
        i++
    }
    
    log.debug "Done waiting." + "Current login response: " + atomicState.loginResponse
    
    //Connection issue
    if (atomicState.loginResponse == null){
    	log.debug "Unable to connect"         
		return dynamicPage(name: "prefLogInWait", title: "Log In", uninstall:false, install: false) {
            section() {
                paragraph "Unable to connect to Rainmachine. Check your local IP and try again"            
            }
        }
    }
    
    //Bad login credentials
    if (atomicState.loginResponse == "Bad Login"){
    	log.debug "Bad Login show on form"      
		return dynamicPage(name: "prefLogInWait", title: "Log In", uninstall:false, install: false) {
            section() {
                paragraph "Bad username/password. Click back and try again."            
            }
        }
    }
    
    //Login Success!
    if (atomicState.loginResponse == "Success"){
		getZonesAndPrograms()
        
        //Wait up to 10 seconds for login response
        i = 0
        while (i < 5){
            pause(2000)
            if (atomicState.zonesResponse == "Success" && atomicState.programsResponse == "Success" ){            
                log.debug "Got a zone response! Let's go!"
                i = 5
            }
            i++
        }
        
        log.debug "Done waiting on zones/programs. zone response: " + atomicState.zonesResponse + " programs response: " + atomicState.programsResponse
        
        return dynamicPage(name: "prefListProgramsZones",  title: "Programs/Zones", install:true, uninstall:true) {
            section("Select which programs to use"){
                input(name: "programs", type: "enum", required:false, multiple:true, metadata:[values:atomicState.ProgramList])
            }
            section("Select which zones to use"){
                input(name: "zones", type: "enum", required:false, multiple:true, metadata:[values:atomicState.ZoneList])
            }
    	}
    }
    
    else{
    	return dynamicPage(name: "prefListProgramsZones", title: "Programs/Zones", uninstall:false, install: false) {
            section() {
                paragraph "Problem getting zone/program data. Click back and try again."
            }
        }
    
    }

}


def parseLoginResponse(response){
	
    log.debug "Reset login info!"
    atomicState.access_token = ""
    atomicState.expires_in = ""
    
    atomicState.loginResponse = 'Received'
    
    if (response.statusCode == 2){
    	atomicState.loginResponse = 'Bad Login'
    }
    
    log.debug "token was "  + response.access_token
    if (response.access_token != null){
    	log.debug "Saving token"
        atomicState.access_token = response.access_token
        log.debug "Login token newly set to: " + atomicState.access_token
        atomicState.expires_in = now() + response.expires_in
    }
	atomicState.loginResponse = 'Success'
    log.debug "Login response set to: " + atomicState.loginResponse
    log.debug "Login token was set to: " + atomicState.access_token
}


def parse(evt) {
    
    //log.debug "Evt: " + evt
    //log.debug "Dev: " + evt.device
    //log.debug "Name: " + evt.name
    //log.debug "Source: " + evt.source
    def description = evt.description
    def hub = evt?.hubId

    //log.debug "cp desc: " + description
    
    def msg = parseLanMessage(evt.description)

    def headersAsString = msg.header // => headers as a string
    def headerMap = msg.headers      // => headers as a Map
    def body = msg.body              // => request body as a string
    def status = msg.status          // => http status code of the response
    def json = msg.json              // => any JSON included in response body, as a data structure of lists and maps
    def xml = msg.xml                // => any XML included in response body, as a document tree structure
    def data = msg.data              // => either JSON or XML in response body (whichever is specified by content-type header in response)
	
    
    if (status == 200 && Body != "OK") {
        
        def slurper = new groovy.json.JsonSlurper()
        def result = slurper.parseText(body)    
        
        if (result.zones){
        	log.debug "Zone response detected!"
            log.debug "result: " + result
            atomicState.zonesResponse = "Success"
        	getZoneList(result.zones)
        }
        
        if (result.programs){
        	log.debug "Program response detected!"
            log.debug "result: " + result
            atomicState.programsResponse = "Success"
            log.debug "Set program status to: " + atomicState.programsResponse
        	getProgramList(result.programs)
        }
        
        if (result.statusCode != null){
        	log.debug "Login response detected!" 
            log.debug "result: " + result
            parseLoginResponse(result)
        }
        
    }
    else if (status == 401){
        log.debug "result: " + body
        atomicState.expires_in =  now() - 500
		atomicState.access_token = "" 		
        atomicState.loginResponse = 'Bad Login'        
    }
    else if (status != 411 && body != null){
    	log.debug "Unexpected response! " + status + " " + body + "evt " + description
    }
    
    
}


def doLogin(){
	atomicState.loginResponse = null
    return doCallout("POST", "/api/4/auth/login", "{\"pwd\": \"" + password + "\",\"remember\": 1 }")
}

def getZonesAndPrograms(){
	atomicState.zonesResponse = null 
    atomicState.programsResponse = null
    log.debug "Getting zones and programs using token: " + atomicState.access_token
    doCallout("GET", "/api/4/zone?access_token=" + atomicState.access_token , "")
    doCallout("GET", "/api/4/program?access_token=" + atomicState.access_token , "")
}

/* Initialization */
def installed() {
	log.info  "installed()"
	log.debug "Installed with settings: " + settings
    unschedule()
}

def updated() {
	log.info  "updated()"
	log.debug "Updated with settings: " + settings
    atomicState.polling = [ 
		last: now(),
		runNow: true
	]
    //unschedule()
	//unsubscribe()	
	initialize()
}

def uninstalled() {
	def delete = getAllChildDevices()
	delete.each { deleteChildDevice(it.deviceNetworkId) }
}


def updateMapData(){
	def combinedMap = [:]
    combinedMap << atomicState.ProgramData
    combinedMap << atomicState.ZoneData    
    atomicState.data = combinedMap
}

def initialize() {    
	log.info  "initialize()"

	//Merge Zone and Program data into single map
    //atomicState.data = [:]
    
    def combinedMap = [:]
    combinedMap << atomicState.ProgramData
    combinedMap << atomicState.ZoneData    
    atomicState.data = combinedMap
    
	def selectedItems = []
	def programList = [:] 
	def zoneList = [:]
	def delete 
    
	// Collect programs and zones 
	if (settings.programs) {
		if (settings.programs[0].size() > 1) {
			selectedItems = settings.programs
		} else {
			selectedItems.add(settings.programs)
		}
		programList = atomicState.ProgramList
	}
	if (settings.zones) {
		if (settings.zones[0].size() > 1) {
			settings.zones.each { dni -> selectedItems.add(dni)}
		} else {
			selectedItems.add(settings.zones)
		}
		zoneList = atomicState.ZoneList
	}
    
	// Create device if selected and doesn't exist
	selectedItems.each { dni ->    	
		def childDevice = getChildDevice(dni)
		def childDeviceAttrib = [:]
		if (!childDevice) {
			if (dni.contains("prog")) {
				childDeviceAttrib = ["name": "RainMachine Program: " + programList[dni], "completedSetup": true]
			} else if (dni.contains("zone")) {
				childDeviceAttrib = ["name": "RainMachine Zone: " + zoneList[dni], "completedSetup": true]
			}
			addChildDevice("copy-ninja", "RainMachine", dni, null, childDeviceAttrib)
		}         
	}
    
	// Delete child devices that are not selected in the settings
	if (!selectedItems) {
		delete = getAllChildDevices()
	} else {
		delete = getChildDevices().findAll { 
			!selectedItems.contains(it.deviceNetworkId) 
		}
	}
	delete.each { deleteChildDevice(it.deviceNetworkId) }
    
    
    //Subscribes to sunrise and sunset event to trigger refreshes
	//subscribe(location, "sunrise", monitorTheMonitor)
	//subscribe(location, "sunset", monitorTheMonitor)
	//subscribe(location, "mode", monitorTheMonitor)
	//subscribe(location, "sunriseTime", monitorTheMonitor)
	//subscribe(location, "sunsetTime", monitorTheMonitor)
    
    //Reset monitoring timestamp
    //atomicState.lastMonitored = now()
    
    // Schedule polling
	//schedulePoll()
    //schedule("19 0/" + 5 + " * * * ?", monitorPoll )
	
}


/* Access Management */
private loginTokenExists(){
	log.debug "Checking for token: " + atomicState.auth
    return (atomicState.access_token != null && atomicState.expires_in != null && atomicState.expires_in > now()) 
}


def doCallout(calloutMethod, urlPath, calloutBody){
	subscribe(location, null, parse, [filterEvents:false])
    log.info  "Calling out to " + ip_address + urlPath
    
    //192.168.1.74:8
    
    def httpRequest = [
      	method: calloutMethod,
    	path: urlPath,
        headers:	[
        				HOST: ip_address + ":80",
                        "Content-Type": "application/json",                        
						Accept: 	"*/*",
                    ],
        body: calloutBody
	]
    
	def hubAction = new physicalgraph.device.HubAction(httpRequest)
    //log.debug "hubaction: " + hubAction
	return sendHubCommand(hubAction)
}


// Listing all the programs you have in RainMachine
def getProgramList(programs) {	
    //atomicState.ProgramData = [:]
    def tempList = [:]
    
    def programsList = [:]
    programs.each { program ->
        if (program.uid) {
            def dni = [ app.id, "prog", program.uid ].join('|')
            def endTime = 0 //TODO: calculate time left for the program                             
            
            programsList[dni] = program.name
            
            tempList[dni] = [
                status: program.status,
                endTime: endTime,
                lastRefresh: now()
            ]

            log.debug "Prog: " + dni + "   Status : " + tempList[dni]
            
        }
	}
	atomicState.ProgramList = programsList    
    atomicState.ProgramData = tempList
    
    log.debug "temp list reviewed! " + atomicState.ProgramList
    log.debug "atomic data reviewed! " + atomicState.ProgramData    
    
    //log.debug "atomic data reviewed! " + atomicState.data    
    //pollAllChild()
}

// Listing all the zones you have in RainMachine
def getZoneList(zones) {
	atomicState.ZoneData = [:]
    def tempList = [:]
    def zonesList = [:]
    zones.each { zone ->
        def dni = [ app.id, "zone", zone.uid ].join('|')
        def endTime = now + ((zone.remaining?:0) * 1000)
        zonesList[dni] = zone.name
        tempList[dni] = [
            status: zone.state,
            endTime: endTime,
            lastRefresh: now()
        ]
        log.debug "Zone: " + dni + "   Status : " + tempList[dni]
    }	   
	atomicState.ZoneList = zonesList
    atomicState.ZoneData = tempList
    log.debug "State zone list: " + atomicState.zonesList
}

// Updates devices
def updateDeviceData() {
	log.info "updateDeviceData()"
	// automatically checks if the token has expired, if so login again
    if (login()) {        
        // Next polling time, defined in settings
        def next = (atomicState.polling.last?:0) + ( (settings.polling.toInteger() > 0 ? settings.polling.toInteger() : 1)  * 60 * 1000)
        log.debug "last: " + atomicState.polling.last
        log.debug "now: " + new Date( now() * 1000 ) 
        log.debug "next: " + next       
        log.debug "RunNow: " + atomicState.polling.runNow       
        if ((now() > next) || (atomicState.polling.runNow)) {
        	
            // set polling states
            atomicState.polling = [ 
            	last: now(),
                runNow: false
            ]

            // Get all the program information
            getProgramList()

            // Get all the program information
            getZoneList()
            
        }        
	}
}

def pollAllChild() {
    // get all the children and send updates    
    def childDevice = getAllChildDevices()
    childDevice.each { 
    	log.debug "Updating children " + it.deviceNetworkId
        //sendAlert("Trying to set last refresh to: " + atomicState.data[it.deviceNetworkId].lastRefresh)
        it.updateDeviceStatus(atomicState.data[it.deviceNetworkId].status)
        it.updateDeviceLastRefresh(atomicState.data[it.deviceNetworkId].lastRefresh)        
        //it.poll()
    }
}

// Returns UID of a Zone or Program
private getChildUID(child) {
	return child.device.deviceNetworkId.split("\\|")[2]
}

// Returns Type of a Zone or Program
private getChildType(child) {
	def childType = child.device.deviceNetworkId.split("\\|")[1]
	if (childType == "prog") { return "program" }
	if (childType == "zone") { return "zone" }
}



/* for SmartDevice to call */
// Refresh data
def refresh() {	
    log.info "refresh()"
    
	atomicState.polling = [ 
		last: now(),
		runNow: true
	]
	//atomicState.data = [:]
    
    
    
    //If login token exists and is valid, reuse it and callout to refresh zone and program data
    if (loginTokenExists()){
		log.debug "Existing token detected"
        getZonesAndPrograms()
        
        //Wait up to 10 seconds before cascading results to child devices
        def i = 0
        while (i < 5){
            pause(2000)
            if (atomicState.zonesResponse == "Success" && atomicState.programsResponse == "Success" ){            
                log.debug "Got a zone response! Let's go!"
                updateMapData()
                pollAllChild()
                return true
            }
            i++
        }
        
        if (atomicState.zonesResponse == null){
    		log.debug "Unable to get zone data while trying to refresh"
            return false
    	}
        
        if (atomicState.programsResponse == null){
    		log.debug "Unable to get zone data while trying to refresh"
            return false
    	}
    	
    }
    
    //If not, get a new token then refresh
    else{
    	log.debug "Need new token"
    	doLogin()
        
        //Wait up to 20 seconds for successful login
        def i  = 0   
        while (i < 5){
            pause(2000)
            if (atomicState.loginResponse != null){
                log.debug "Got a response! Let's go!"
                i = 5
            }
            i++
        }
        log.debug "Done waiting." + "Current login response: " + atomicState.loginResponse
        
        
        if (atomicState.loginResponse == null){
    		log.debug "Unable to connect while trying to refresh zone/program data"
            return false
    	}
    
    
        if (atomicState.loginResponse == "Bad Login"){
            log.debug "Bad Login while trying to refresh zone/program data"      
            return false
        }
        
        
        if (atomicState.loginResponse == "Success"){
            log.debug "Got a zone response! Let's go!"
            refresh()
    	}
        
    }
    
    //Update Devices
	//updateDeviceData()
    
   
}

// Get single device status
def getDeviceStatus(child) {
	log.info "getDeviceStatus()"
	//tries to get latest data if polling limitation allows
	//updateDeviceData()
	return atomicState.data[child.device.deviceNetworkId].status
}

// Get single device refresh timestamp
def getDeviceLastRefresh(child) {
	log.info "getDeviceStatus()"
	//tries to get latest data if polling limitation allows
	//updateDeviceData()
	return atomicState.data[child.device.deviceNetworkId].lastRefresh
}






// Get single device ending time
def getDeviceEndTime(child) {
	//tries to get latest data if polling limitation allows
	updateDeviceData()
	if (atomicState.data[child.device.deviceNetworkId]) {
		return atomicState.data[child.device.deviceNetworkId].endTime
	}
}

// Send command to start or stop
def sendCommand(child, apiCommand, apiTime) {
	def childUID = getChildUID(child)
	def childType = getChildType(child)
	def commandSuccess = false
	def zonesActive = false
	def apiPath = "/api/4/" + childType + "/" + childUID + "/" + apiCommand
	def apiBody = []
    
	//Try to get the latest data first
	updateDeviceData()    
	
	//Checks for any active running sprinklers before allowing another program to run
	if (childType == "program") {
		if (apiCommand == "start") { 
			atomicState.data.each { dni, data -> if ((data.status == 1) || (data.status == 2)) { zonesActive = true }}
			if (!zonesActive) {
				apiPost(apiPath, [pid: childUID]) 
				commandSuccess = true
			} else {
				commandSuccess = false
			}        
		} else {
			apiPost(apiPath, [pid: childUID]) 
			commandSuccess = true
		}
	} 
	
	//Zones will require time
	if (childType == "zone") {
		apiPost(apiPath, [time: apiTime])
		commandSuccess = true
	}  
    
	//Forcefully get the latest data after waiting for 2 seconds
	pause(2000)
	refresh()
	
	return commandSuccess
}

//Stop everything
def sendStopAll() {
	def apiPath = "/api/4/watering/stopall"
	def apiBody = [all: "true"]
	apiPost(apiPath, apiBody)
	
	//Forcefully get the latest data after waiting for 2 seconds
	pause(2000)
	refresh()
	return true
}

def monitorPoll(){
    try {                
        log.debug "Monitoring the poll...Last poll stamp: " + atomicState.polling.last
        if (now() > atomicState.polling.last + ((settings.polling.toInteger() > 0 )? settings.polling.toInteger() : 1)*100000*2){
            log.debug "RainMachine polling schedule needs reboot!"
            sendAlert("RainMachine schedule is dead! Restart!")
            reSchedulePoll()
        }
        atomicState.lastMonitored = now()
    } catch (Error e)	{
		log.debug "Error in RainMachine monitorPoll: $e"
        sendAlert("Error in RainMachine monitorPoll: $e")
	}
    
}

private schedulePoll() {
    log.debug "Creating RainMachine schedule..."
    unschedule()
	schedule("37 0/" + ((settings.polling.toInteger() > 0 )? settings.polling.toInteger() : 1)  + " * * * ?", refresh )
    log.debug "RainMachine schedule successfully started!"   
}

private reSchedulePoll() {
    try {
        log.debug "Attempting to recreate the RainMachine schedule..."
        schedule("37 0/" + ((settings.polling.toInteger() > 0 )? settings.polling.toInteger() : 1)  + " * * * ?", refresh )
        log.debug "RainMachine schedule successfully restarted!"
        sendAlert("RainMachine schedule successfully restarted!")
	} catch (Error e)	{
		log.debug "Error restarting RainMachine schedule: $e"
        sendAlert("Error restarting RainMachine schedule: $e")
	}
}

//Last line of defense against SDSS
public monitorTheMonitor(evt){
	try {                
        log.debug "Event " + evt.displayName + " triggered monitoring the rainmachine monitor...Last poll stamp: " + atomicState.lastMonitored
        if (now() > atomicState.lastMonitored + 480000){
            log.debug "RainMachine monitor schedule needs reboot!"
            sendAlert("RainMachine monitor schedule is dead! Restart!")
            reScheduleMonitor()
        }        
    } catch (Error e)	{
		log.debug "Error in RainMachine monitorPoll: $e"
        sendAlert("Error in RainMachine monitorPoll: $e")
	}
}


private reScheduleMonitor() {
    try {
        log.debug "Attempting to recreate the RainMachine monitor..."
        schedule("19 0/" + 5 + " * * * ?", monitorPoll )
        log.debug "RainMachine monitor successfully restarted!"
        sendAlert("RainMachine monitor successfully restarted!")
	} catch (Error e)	{
		log.debug "Error restarting RainMachine monitor: $e"
        sendAlert("Error restarting RainMachine monitor: $e")
	}
}

def sendAlert(alert){
	sendSms("615-828-5772", "Alert: " + alert)
}
