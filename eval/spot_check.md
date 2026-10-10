# Spot-check list (30 cases)

Read each transcript and check the gold form against the extraction rules (PRD §13.5): only stated fields, English values, names/tags as spoken, `time` = spoken phrase. Tick the box or fix `eval/scenarios.py` and re-run `python eval/gen_cases.py`.

## [ ] slide-000 — WATER · en · clean

> There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.

```json
{
 "lang": "en",
 "incident_type": "WATER",
 "title": "Water leak near Conference Room 2",
 "description": "A water leak occurred near Conference Room 2. Maintenance has isolated the area.",
 "location": "near Conference Room 2",
 "time": "around 10 AM",
 "actions_taken": [
  "Maintenance isolated the area"
 ]
}
```
Follow-up — Q: *Was anyone hurt, and how serious is the situation right now?*  A: *No one is hurt, it's minor.*

```json
{"lang": "en", "set": {"injuries": "NO", "severity": "LOW"}}
```

## [ ] tc-064 — ELECTRICAL · en · clean

> The main distribution board near Electrical Panel DB-3 is overheating, it is very hot to touch and humming loudly. The electrician isolated it. No one was injured.

```json
{
 "lang": "en",
 "incident_type": "ELECTRICAL",
 "title": "Overheating panel near Electrical Panel DB-3",
 "description": "An electrical panel near Electrical Panel DB-3 is overheating.",
 "location": "near Electrical Panel DB-3",
 "injuries": "NO",
 "actions_taken": [
  "Electrician isolated the panel"
 ],
 "details": {
  "electrical_hazard": "OVERHEATING",
  "power_isolated": "YES",
  "affected_circuit": "main distribution board"
 }
}
```

## [ ] tc-053 — ELECTRICAL · hinglish · clean

> Subah lagbhag 10 baje QC Lab ke bahar ek wall socket mein spark aane laga. Switch abhi bhi on hai. Koi ghayal nahi hua.

```json
{
 "lang": "hinglish",
 "incident_type": "ELECTRICAL",
 "title": "Sparking socket outside the QC Lab",
 "description": "A socket outside the QC Lab is sparking.",
 "location": "outside QC Lab",
 "time": "around 10 AM",
 "injuries": "NO",
 "details": {
  "electrical_hazard": "SPARKING",
  "power_isolated": "NO",
  "affected_circuit": "wall socket"
 }
}
```
Follow-up — Q: *Abhi situation kitni serious hai — chhoti problem, badi problem, ya kisi ki jaan ko khatra hai?*  A: *Kaafi serious hai.*

```json
{"lang": "hinglish", "set": {"severity": "HIGH"}}
```

## [ ] tc-088 — ENVIRONMENTAL · hinglish · clean

> Kisi ne Warehouse ke paas purane chemical waste ke drum phenk diye hain aur mitti par leak ho rahe hain, maine kal raat 11 baje dekha. Lagbhag 2 drum hain. Abhi bhi leak ho raha hai ya nahi, pata nahi.

```json
{
 "lang": "hinglish",
 "incident_type": "ENVIRONMENTAL",
 "title": "Chemical waste dumped near the Warehouse",
 "description": "Drums of chemical waste were dumped near the Warehouse and are leaking onto the soil.",
 "location": "near Warehouse",
 "time": "last night at 11",
 "details": {
  "pollutant": "chemical waste from old drums",
  "release_medium": "SOIL",
  "containment_status": "UNKNOWN",
  "env_quantity": "about 2 drums"
 }
}
```

## [ ] tc-061 — ENVIRONMENTAL · en · clean

> Untreated effluent is overflowing from the tank outside the Effluent Treatment Plant onto the open ground. We stopped the inlet pump so it has stopped now.

```json
{
 "lang": "en",
 "incident_type": "ENVIRONMENTAL",
 "title": "Untreated effluent overflow at ETP",
 "description": "Untreated effluent overflowed from the ETP onto the soil.",
 "location": "outside Effluent Treatment Plant",
 "actions_taken": [
  "Stopped the inlet pump"
 ],
 "details": {
  "pollutant": "untreated effluent",
  "release_medium": "SOIL",
  "containment_status": "STOPPED"
 }
}
```
Follow-up — Q: *Was anyone hurt, and how serious is the situation right now?*  A: *It's minor, nothing serious. I don't know if anyone is hurt.*

```json
{"lang": "en", "set": {"severity": "LOW"}, "unknown": ["injuries"]}
```

## [ ] tc-041 — EQUIPMENT · en · clean

> CNC machine M-7 behind the QC Lab has a spindle problem around 3 in the afternoon and is producing faulty parts. It is still running at low speed.

```json
{
 "lang": "en",
 "incident_type": "EQUIPMENT",
 "title": "CNC machine M-7 malfunction",
 "description": "CNC machine M-7 behind the QC Lab is producing faulty parts due to a spindle problem.",
 "location": "behind QC Lab",
 "time": "around 3 in the afternoon",
 "equipment": [
  "CNC machine M-7"
 ],
 "details": {
  "failure_description": "spindle problem producing faulty parts",
  "operational_status": "DEGRADED"
 }
}
```

## [ ] tc-001 — EQUIPMENT · en · noisy

> umm the conveyor belt cv-1 near bay you know 1 jammed this morning the motor is like making a grinding noise it is is running uh slowly nobody is heart

```json
{
 "lang": "en",
 "incident_type": "EQUIPMENT",
 "title": "Conveyor CV-1 jammed near Bay 1",
 "description": "Conveyor CV-1 jammed near Bay 1.",
 "location": "near Bay 1",
 "time": "this morning",
 "injuries": "NO",
 "equipment": [
  "conveyor belt CV-1"
 ],
 "details": {
  "failure_description": "conveyor belt jammed and motor making a grinding noise",
  "operational_status": "DEGRADED"
 }
}
```
Follow-up — Q: *How serious is it right now — minor, significant, or is anyone in danger?*  A: *Skip this, I'll tell you later.*

```json
{"lang": "en", "set": {}, "skipped": true}
```

## [ ] tc-011 — FIRE · hinglish · clean

> Bay 3 par aag lag gayi aur Suresh Kumar ka kamar jal gaya. Aag abhi bhi jal rahi hai. Suresh Kumar hosh mein hai aur baat kar raha hai. First aid de diya hai. Ambulance bula li hai.

```json
{
 "lang": "hinglish",
 "incident_type": "FIRE",
 "secondary_types": [
  "MEDICAL"
 ],
 "title": "Fire at Bay 3 with burn injury",
 "description": "A fire broke out at Bay 3 and Suresh Kumar suffered burns.",
 "location": "Bay 3",
 "injuries": "YES",
 "persons": [
  {
   "name": "Suresh Kumar",
   "role": "AFFECTED",
   "injured": true,
   "injury": "burns on back"
  }
 ],
 "actions_taken": [
  "First aid given",
  "Ambulance called"
 ],
 "details": {
  "fire_status": "ACTIVE",
  "patient_condition": "CONSCIOUS_STABLE",
  "first_aid_given": "YES",
  "ambulance_called": "YES",
  "injury_nature": "burns",
  "body_part": "back"
 }
}
```

## [ ] tc-059 — FIRE · en · clean

> The microwave near the Cafeteria caught fire at 2:15 PM. There is a lot of smoke but the flames are gone. We did not need to evacuate. Nobody is hurt, it is a minor thing.

```json
{
 "lang": "en",
 "incident_type": "FIRE",
 "title": "Small fire near the Cafeteria",
 "description": "A microwave caught fire near the Cafeteria.",
 "location": "near Cafeteria",
 "time": "at 2:15 PM",
 "severity": "LOW",
 "injuries": "NO",
 "details": {
  "fire_status": "SMOKE_ONLY",
  "evacuation_status": "NOT_REQUIRED",
  "ignition_source": "microwave"
 }
}
```
Follow-up — Q: *Has the fire brigade been called?*  A: *Yes, the fire brigade is on the way.*

```json
{"lang": "en", "set": {"fire_brigade_called": "YES"}}
```

## [ ] tc-034 — HAZMAT · hinglish · clean

> Abhi abhi Chemical Store ke bahar drum kholte waqt Arjun Nair ne chemical ka dhuan saans mein le liya aur bahut khaans raha hai. Drum band kar diya hai. Arjun Nair hosh mein hai. Arjun Nair ne mask nahi pehna tha. First aid diya ya nahi, pata nahi.

```json
{
 "lang": "hinglish",
 "incident_type": "HAZMAT",
 "secondary_types": [
  "MEDICAL"
 ],
 "title": "Arjun Nair inhaled chemical fumes outside the Chemical Store",
 "description": "Arjun Nair inhaled chemical fumes outside the Chemical Store and is coughing badly.",
 "location": "outside Chemical Store",
 "time": "just now",
 "injuries": "YES",
 "persons": [
  {
   "name": "Arjun Nair",
   "role": "AFFECTED",
   "injured": true
  }
 ],
 "actions_taken": [
  "Drum closed"
 ],
 "details": {
  "patient_condition": "CONSCIOUS_DISTRESSED",
  "first_aid_given": "UNKNOWN",
  "substance": "chemical fumes from a drum",
  "release_status": "STOPPED",
  "exposure_symptoms": "coughing",
  "ppe_used": "NO"
 }
}
```

## [ ] tc-004 — HAZMAT · en · clean

> There is an ammonia leak in the Warehouse at around 11:30 in the morning, the smell is very strong. People are coughing and their eyes are burning. We have evacuated the area.

```json
{
 "lang": "en",
 "incident_type": "HAZMAT",
 "title": "Ammonia leak in the Warehouse",
 "description": "Ammonia gas is leaking in the Warehouse.",
 "location": "Warehouse",
 "time": "around 11:30 in the morning",
 "severity": "CRITICAL",
 "actions_taken": [
  "Area evacuated"
 ],
 "hazard_ongoing": "YES",
 "details": {
  "substance": "ammonia",
  "release_status": "ONGOING",
  "area_evacuated": "YES",
  "exposure_symptoms": "coughing and burning eyes"
 }
}
```
Follow-up — Q: *Was anyone hurt?*  A: *No one is hurt.*

```json
{"lang": "en", "set": {"injuries": "NO"}}
```

## [ ] tc-130 — MEDICAL · en · clean

> A forklift hit Priya Sharma at the Loading Dock about half an hour ago and her head is injured. The forklift driver was Rajesh Gupta. She is conscious but cannot stand. The ambulance has been called.

```json
{
 "lang": "en",
 "incident_type": "MEDICAL",
 "secondary_types": [
  "VEHICLE"
 ],
 "title": "Priya Sharma hit by a forklift at the Loading Dock",
 "description": "A forklift hit Priya Sharma at the Loading Dock, injuring her head.",
 "location": "Loading Dock",
 "time": "about half an hour ago",
 "injuries": "YES",
 "persons": [
  {
   "name": "Priya Sharma",
   "role": "AFFECTED",
   "injured": true
  },
  {
   "name": "Rajesh Gupta",
   "role": "INVOLVED"
  }
 ],
 "actions_taken": [
  "Ambulance called"
 ],
 "details": {
  "patient_condition": "CONSCIOUS_DISTRESSED",
  "ambulance_called": "YES",
  "body_part": "head",
  "injury_cause": "hit by a forklift",
  "vehicles_involved": [
   "forklift"
  ],
  "collision_type": "VEHICLE_PERSON",
  "driver_name": "Rajesh Gupta"
 }
}
```
Follow-up — Q: *How serious is it right now — minor, significant, or is anyone in danger?*  A: *It's quite serious.*

```json
{"lang": "en", "set": {"severity": "HIGH"}}
```

## [ ] tc-002 — MEDICAL · hinglish · noisy

> toh loading dock ke paas forklift ne vikram singh ko takkar maar di uske pair pair mein chot chot hai forklift ramesh chala rha tha vikram acha singh hosh mein he lekin khada nahin nahin toh ho pa raha ambulance bulayi gayi hai

```json
{
 "lang": "hinglish",
 "incident_type": "MEDICAL",
 "secondary_types": [
  "VEHICLE"
 ],
 "title": "Vikram Singh hit by a forklift near the Loading Dock",
 "description": "A forklift hit Vikram Singh near the Loading Dock, injuring his leg.",
 "location": "near Loading Dock",
 "injuries": "YES",
 "persons": [
  {
   "name": "Vikram Singh",
   "role": "AFFECTED",
   "injured": true
  },
  {
   "name": "Ramesh",
   "role": "INVOLVED"
  }
 ],
 "actions_taken": [
  "Ambulance called"
 ],
 "details": {
  "patient_condition": "CONSCIOUS_DISTRESSED",
  "ambulance_called": "YES",
  "body_part": "leg",
  "injury_cause": "hit by a forklift",
  "vehicles_involved": [
   "forklift"
  ],
  "collision_type": "VEHICLE_PERSON",
  "driver_name": "Ramesh"
 }
}
```

## [ ] tc-107 — OTHER · hinglish · noisy

> woh dopahar sawa do baje open workspace toh ke paas fire alarm baj gaya lekin aag nahi thi false alarm alarm tha ek smoke haan toh detector kharab hai kuch serious nahi kisi kisi ko chot nahin

```json
{
 "lang": "hinglish",
 "incident_type": "OTHER",
 "title": "False fire alarm near the Open Workspace",
 "description": "The fire alarm went off by mistake near the Open Workspace; there was no fire.",
 "location": "near Open Workspace",
 "time": "at 2:15 PM",
 "severity": "LOW",
 "injuries": "NO"
}
```

## [ ] tc-078 — OTHER · hinglish · clean

> Terrace par ek bada madhumakhi ka chhatta ban gaya hai, maine do ghante pehle dekha. Abhi tak kisi ko dank nahi laga.

```json
{
 "lang": "hinglish",
 "incident_type": "OTHER",
 "title": "Beehive at the Terrace",
 "description": "A large beehive has formed at the Terrace.",
 "location": "Terrace",
 "time": "2 hours ago",
 "injuries": "NO"
}
```
Follow-up — Q: *Abhi situation kitni serious hai — chhoti problem, badi problem, ya kisi ki jaan ko khatra hai?*  A: *Medium hai, abhi control mein hai.*

```json
{"lang": "hinglish", "set": {"severity": "MEDIUM"}}
```

## [ ] tc-094 — SECURITY · hinglish · clean

> Aadha ghanta pehle Loading Dock par ek anjaan aadmi deewar kood ke andar aaya. Guard Ravi Shankar ne usko dekha aur security office ko bataya. Police ko bulaya ya nahi, pata nahi.

```json
{
 "lang": "hinglish",
 "incident_type": "SECURITY",
 "title": "Intruder at the Loading Dock",
 "description": "An unknown person entered the premises at the Loading Dock.",
 "location": "Loading Dock",
 "time": "about half an hour ago",
 "persons": [
  {
   "name": "Ravi Shankar",
   "role": "RESPONDER"
  }
 ],
 "actions_taken": [
  "Guard Ravi Shankar informed the security office"
 ],
 "details": {
  "security_event": "INTRUSION",
  "security_informed": "YES",
  "police_informed": "UNKNOWN"
 }
}
```

## [ ] tc-089 — SECURITY · en · clean

> Anil Verma and Anjali Mehta got into a physical fight at the Loading Dock about an hour ago. Anjali Mehta has a small cut on the lip. Security separated them. Also, separately, the washroom tap on the ground floor is broken.

```json
{
 "lang": "en",
 "incident_type": "SECURITY",
 "title": "Fight between workers at the Loading Dock",
 "description": "Anil Verma and Anjali Mehta got into a physical fight at the Loading Dock.",
 "location": "Loading Dock",
 "time": "about an hour ago",
 "injuries": "YES",
 "persons": [
  {
   "name": "Anil Verma",
   "role": "INVOLVED"
  },
  {
   "name": "Anjali Mehta",
   "role": "INVOLVED",
   "injured": true,
   "injury": "small cut on the lip"
  }
 ],
 "actions_taken": [
  "Security separated them"
 ],
 "other_incidents": [
  "Ground floor washroom tap is broken"
 ],
 "details": {
  "security_event": "ASSAULT",
  "security_informed": "YES"
 }
}
```

## [ ] tc-131 — STRUCTURAL · hinglish · clean

> Warehouse ke bahar deewar mein ek badi nayi darar aa gayi hai, maine ek ghanta pehle dekhi. Kisi ne usko band nahi kiya. Kisi ko chot nahi lagi, abhi minor hai.

```json
{
 "lang": "hinglish",
 "incident_type": "STRUCTURAL",
 "title": "Crack in the wall outside the Warehouse",
 "description": "A large crack has appeared in the wall outside the Warehouse.",
 "location": "outside Warehouse",
 "time": "about an hour ago",
 "severity": "LOW",
 "injuries": "NO",
 "details": {
  "damage_type": "large crack in the wall",
  "area_cordoned": "NO"
 }
}
```

## [ ] tc-046 — STRUCTURAL · en · clean

> A false ceiling panel fell down behind the Open Workspace two hours ago. I don't know if anyone has blocked the area.

```json
{
 "lang": "en",
 "incident_type": "STRUCTURAL",
 "title": "Ceiling panel fell behind the Open Workspace",
 "description": "A false ceiling panel fell behind the Open Workspace.",
 "location": "behind Open Workspace",
 "time": "2 hours ago",
 "details": {
  "damage_type": "false ceiling panel fell",
  "area_cordoned": "UNKNOWN"
 }
}
```

## [ ] tc-070 — VEHICLE · hinglish · clean

> 15 minute pehle Parking Area ke bahar ek car aur bike ki takkar ho gayi. Sirf gaadiyon ka nuksaan hua, kisi ko chot nahi lagi.

```json
{
 "lang": "hinglish",
 "incident_type": "VEHICLE",
 "title": "Car and bike collision outside the Parking Area",
 "description": "A car and a motorbike collided outside the Parking Area.",
 "location": "outside Parking Area",
 "time": "15 minutes ago",
 "severity": "LOW",
 "injuries": "NO",
 "details": {
  "vehicles_involved": [
   "car",
   "motorbike"
  ],
  "collision_type": "VEHICLE_VEHICLE"
 }
}
```

## [ ] tc-003 — VEHICLE · en · clean

> A car and a motorbike collided near the Main Gate around 3 in the afternoon. The car number is KA 05 MN 7788. Only the vehicles are damaged, nobody is hurt.

```json
{
 "lang": "en",
 "incident_type": "VEHICLE",
 "title": "Car and bike collision near the Main Gate",
 "description": "A car and a motorbike collided near the Main Gate.",
 "location": "near Main Gate",
 "time": "around 3 in the afternoon",
 "severity": "LOW",
 "injuries": "NO",
 "details": {
  "vehicles_involved": [
   "car",
   "motorbike"
  ],
  "collision_type": "VEHICLE_VEHICLE",
  "vehicle_ids": [
   "KA 05 MN 7788"
  ]
 }
}
```

## [ ] tc-018 — WATER · en · clean

> There is a water leak at the Store Room from a burst pipe around 10 AM. The leak has stopped now. Nobody is hurt, it is minor.

```json
{
 "lang": "en",
 "incident_type": "WATER",
 "title": "Burst pipe leak at the Store Room",
 "description": "A burst pipe is leaking water at the Store Room.",
 "location": "Store Room",
 "time": "around 10 AM",
 "severity": "LOW",
 "injuries": "NO",
 "details": {
  "leak_source": "burst pipe",
  "leak_status": "STOPPED"
 }
}
```

## [ ] tc-048 — WATER · hinglish · noisy

> toh aadha ghanta pehle water tank area par paani ki tanki overflow ho rahi hai abhi bhi overflow ho rha he

```json
{
 "lang": "hinglish",
 "incident_type": "WATER",
 "title": "Water tank overflow at the Water Tank Area",
 "description": "The water tank at the Water Tank Area is overflowing.",
 "location": "Water Tank Area",
 "time": "about half an hour ago",
 "details": {
  "leak_source": "overflowing water tank",
  "leak_status": "ONGOING"
 }
}
```

## [ ] tc-147 — STRUCTURAL · hinglish · noisy

> staircase a a ke paas deewar mein ek badi nayi darar aa gayi gayi hai maine dopahar sawa do baje dekhi kisi ne ne usko band nahin kiya

```json
{
 "lang": "hinglish",
 "incident_type": "STRUCTURAL",
 "title": "Crack in the wall near Staircase A",
 "description": "A large crack has appeared in the wall near Staircase A.",
 "location": "near Staircase A",
 "time": "at 2:15 PM",
 "details": {
  "damage_type": "large crack in the wall",
  "area_cordoned": "NO"
 }
}
```

## [ ] tc-030 — EQUIPMENT · hinglish · noisy

> ek ghanta pehle boiler house ke paas pump pump p-12 bahut vibrate kr rha hai aur seal se leak ho ho rha hai abhi bhi chal rha he standby pump chalu kar diya he koi ghayal nahin hua

```json
{
 "lang": "hinglish",
 "incident_type": "EQUIPMENT",
 "title": "Pump P-12 vibrating and leaking",
 "description": "Pump P-12 near the Boiler House is vibrating heavily and leaking from the seal.",
 "location": "near Boiler House",
 "time": "about an hour ago",
 "injuries": "NO",
 "equipment": [
  "Pump P-12"
 ],
 "actions_taken": [
  "Switched to the standby pump"
 ],
 "details": {
  "failure_description": "heavy vibration and seal leak",
  "operational_status": "RUNNING"
 }
}
```

## [ ] tc-021 — SECURITY · en · noisy

> mohammed irfan and gurpreet singh got into a physical fight at the main gate fifteen minutes ago gurpreet singh has a a small so cut uh on the lip security separated them

```json
{
 "lang": "en",
 "incident_type": "SECURITY",
 "title": "Fight between workers at the Main Gate",
 "description": "Mohammed Irfan and Gurpreet Singh got into a physical fight at the Main Gate.",
 "location": "Main Gate",
 "time": "15 minutes ago",
 "injuries": "YES",
 "persons": [
  {
   "name": "Mohammed Irfan",
   "role": "INVOLVED"
  },
  {
   "name": "Gurpreet Singh",
   "role": "INVOLVED",
   "injured": true,
   "injury": "small cut on the lip"
  }
 ],
 "actions_taken": [
  "Security separated them"
 ],
 "details": {
  "security_event": "ASSAULT",
  "security_informed": "YES"
 }
}
```
Follow-up — Q: *How serious is it right now — minor, significant, or is anyone in danger?*  A: *It's moderate, under control now.*

```json
{"lang": "en", "set": {"severity": "MEDIUM"}}
```

## [ ] tc-026 — ELECTRICAL · en · noisy

> you know a wall uh socket outside the qc lab started sparkling at 2:15 p m we you know switched off the mcb no one was injured

```json
{
 "lang": "en",
 "incident_type": "ELECTRICAL",
 "title": "Sparking socket outside the QC Lab",
 "description": "A socket outside the QC Lab is sparking.",
 "location": "outside QC Lab",
 "time": "at 2:15 PM",
 "injuries": "NO",
 "actions_taken": [
  "Switched off the MCB"
 ],
 "details": {
  "electrical_hazard": "SPARKING",
  "power_isolated": "YES",
  "affected_circuit": "wall socket"
 }
}
```

## [ ] tc-051 — OTHER · hinglish · clean

> Dopahar sawa do baje bandaron ka jhund Terrace par aa gaya. Woh cheezein idhar udhar phenk rahe hain. Abhi tak kisi ko chot nahi lagi. Aur ek alag baat, parking ke entrance ke paas bada gaddha hai.

```json
{
 "lang": "hinglish",
 "incident_type": "OTHER",
 "title": "Monkeys entered at the Terrace",
 "description": "A group of monkeys entered at the Terrace.",
 "location": "Terrace",
 "time": "at 2:15 PM",
 "injuries": "NO",
 "hazard_ongoing": "YES",
 "other_incidents": [
  "Big pothole near the parking entrance"
 ]
}
```
Follow-up — Q: *Abhi situation kitni serious hai — chhoti problem, badi problem, ya kisi ki jaan ko khatra hai?*  A: *Medium hai, abhi control mein hai.*

```json
{"lang": "hinglish", "set": {"severity": "MEDIUM"}}
```

## [ ] tc-111 — VEHICLE · hinglish · noisy

> fifteen minute pehle bay one haan toh mein mein forklift fl-07 ne storage rack ko thok diya rack bahut tedha umm ho acha gaya hai aur boxes boxes gir gaye hain humne humne haan toh woh aisle umm band kar di hai koi ghayal nahin hua

```json
{
 "lang": "hinglish",
 "incident_type": "VEHICLE",
 "secondary_types": [
  "STRUCTURAL"
 ],
 "title": "Forklift hit a rack in Bay 1",
 "description": "Forklift FL-07 hit a storage rack in Bay 1, damaging it.",
 "location": "Bay 1",
 "time": "15 minutes ago",
 "injuries": "NO",
 "actions_taken": [
  "Closed the aisle"
 ],
 "details": {
  "damage_type": "rack badly bent, boxes fell",
  "vehicles_involved": [
   "forklift"
  ],
  "collision_type": "VEHICLE_OBJECT",
  "vehicle_ids": [
   "FL-07"
  ],
  "traffic_blocked": "YES"
 }
}
```
Follow-up — Q: *Abhi situation kitni serious hai — chhoti problem, badi problem, ya kisi ki jaan ko khatra hai?*  A: *Medium hai, abhi control mein hai.*

```json
{"lang": "hinglish", "set": {"severity": "MEDIUM"}}
```

## [ ] tc-071 — MEDICAL · en · noisy

> umm mohammed irfan suddenly fainted in the open workspace a few minutes ago he is still unconscious ramesh is giving first aid

```json
{
 "lang": "en",
 "incident_type": "MEDICAL",
 "title": "Mohammed Irfan fainted in the Open Workspace",
 "description": "Mohammed Irfan fainted in the Open Workspace.",
 "location": "Open Workspace",
 "time": "a few minutes ago",
 "severity": "CRITICAL",
 "injuries": "YES",
 "persons": [
  {
   "name": "Mohammed Irfan",
   "role": "AFFECTED",
   "injured": true
  },
  {
   "name": "Ramesh",
   "role": "RESPONDER"
  }
 ],
 "actions_taken": [
  "Ramesh is giving first aid"
 ],
 "details": {
  "patient_condition": "UNCONSCIOUS",
  "first_aid_given": "YES"
 }
}
```
