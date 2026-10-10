# Recording script — 30 evaluation clips

**How to record**

- Record in the actual hackathon hall — background noise is good, do not look for a quiet corner.
- Hold the phone about **20 cm** from your mouth, speak naturally (as if reporting to a supervisor). Small hesitations, "umm", restarts are fine — do not read robotically.
- Any format is fine (m4a, wav, mp3, ogg). Keep each clip **under 30 seconds** (P0 cap).
- File name **exactly** `clip_NN.<ext>` (e.g. `clip_07.m4a`) and drop the file into `eval/clips/raw/`.
- Clips 01–15 are English, 16–30 are Roman Hinglish (say them the way you would naturally speak Hinglish).
- If you change the wording while speaking, note it next to the clip number in this file so the gold transcript can be fixed in `eval/clips.jsonl`.
- Then run `bash eval/convert_clips.sh` (WSL) to produce 16 kHz mono WAVs in `eval/clips/`.

| # | Lang | Category | Notes |
|---|---|---|---|
| 01 | en | FIRE |  |
| 02 | en | VEHICLE | relative time |
| 03 | en | EQUIPMENT | relative time |
| 04 | en | MEDICAL | **emergency — sound urgent**, relative time |
| 05 | en | FIRE |  |
| 06 | en | OTHER |  |
| 07 | en | HAZMAT | contains "don't know" |
| 08 | en | VEHICLE |  |
| 09 | en | HAZMAT | relative time |
| 10 | en | ELECTRICAL | relative time |
| 11 | en | WATER | relative time |
| 12 | en | WATER |  |
| 13 | en | ELECTRICAL | relative time |
| 14 | en | VEHICLE |  |
| 15 | en | SECURITY | relative time |
| 16 | hinglish | OTHER | relative time |
| 17 | hinglish | ELECTRICAL |  |
| 18 | hinglish | MEDICAL |  |
| 19 | hinglish | FIRE | relative time |
| 20 | hinglish | STRUCTURAL |  |
| 21 | hinglish | HAZMAT | **emergency — sound urgent**, relative time |
| 22 | hinglish | ENVIRONMENTAL | relative time |
| 23 | hinglish | MEDICAL | relative time |
| 24 | hinglish | EQUIPMENT |  |
| 25 | hinglish | HAZMAT | contains "don't know", relative time |
| 26 | hinglish | SECURITY |  |
| 27 | hinglish | STRUCTURAL |  |
| 28 | hinglish | ENVIRONMENTAL |  |
| 29 | hinglish | EQUIPMENT |  |
| 30 | hinglish | WATER |  |

### clip_01  (en, FIRE)

> The motor of CNC machine M-14 near the Boiler House caught fire last night at 11. The machine has stopped completely. The fire is under control now. No one was injured.

### clip_02  (en, VEHICLE)

> So, forklift FL-07 hit a storage rack in the Warehouse two hours ago. The driver was Gurpreet Singh. The rack is badly bent and boxes have fallen.

### clip_03  (en, EQUIPMENT)

> Compressor C-202 at the DG Set Room tripped two hours ago and is not restarting. Bay 2 production has stopped because there is no air pressure. This is serious, production is stopped.

### clip_04  (en, MEDICAL)

> Anjali Mehta suddenly fainted in the Open Workspace about half an hour ago, umm, she is still unconscious. Sandeep is giving first aid.

### clip_05  (en, FIRE)

> The microwave in the Cafeteria caught fire this morning. There is a lot of smoke but the flames are gone. We did not need to evacuate. Nobody is hurt, it is a minor thing.

### clip_06  (en, OTHER)

> So, the fire alarm went off behind the Open Workspace but there was no fire, it was a false alarm. Nothing serious, nobody hurt.

### clip_07  (en, HAZMAT)

> There is a strong smell of LPG gas near the Cafeteria around 3 in the afternoon, the cylinder is leaking. Sunita closed the regulator and the leak has stopped. I don't know if people have been moved out. Nobody is hurt.

### clip_08  (en, VEHICLE)

> A forklift came around the corner very fast in the Warehouse around 10 AM and almost hit Priya Sharma. Nobody was hurt, it was a near miss. The driver was Ravi Shankar.

### clip_09  (en, HAZMAT)

> A bottle of caustic soda solution fell and broke in the QC Lab about an hour ago, umm, we contained it with the spill kit. Ramesh has eye irritation. We were wearing gloves and goggles.

### clip_10  (en, ELECTRICAL)

> So, the main distribution board near the Server Room is overheating two hours ago, it is very hot to touch and humming loudly. The electrician isolated it. Nobody is hurt.

### clip_11  (en, WATER)

> The AC unit AC-7 is dripping water at the Open Workspace a few minutes ago. It is still dripping onto the desks. There is a computer and some cables under it. No one was injured.

### clip_12  (en, WATER)

> The water tank behind the Water Tank Area is overflowing at 2:15 PM. It is still overflowing. No one was injured.

### clip_13  (en, ELECTRICAL)

> There has been a power cut in the whole of Block A since about an hour ago. Nobody is hurt.

### clip_14  (en, VEHICLE)

> So, a car and a motorbike collided near the Main Gate at around 11:30 in the morning.

### clip_15  (en, SECURITY)

> Copper cables were stolen at the Store Room about an hour ago. The lock was broken. Security has been informed.

### clip_16  (hinglish, OTHER)

> Abhi abhi maine Water Tank Area ke paas ek saanp dekha. Kisi ko kuch nahi hua.

### clip_17  (hinglish, ELECTRICAL)

> Dopahar lagbhag teen baje QC Lab ke paas ek wall socket mein spark aane laga. Humne MCB band kar diya. Plastic jalne ki smell aa rahi hai.

### clip_18  (hinglish, MEDICAL)

> Haan toh, dopahar lagbhag teen baje Sandeep Main Reception ke peeche geele farsh par fisal gaya aur uske ghutne mein chot lagi. Mujhe lagta hai fracture ho sakta hai. First aider ne patti kar di hai.

### clip_19  (hinglish, FIRE)

> Do ghante pehle Boiler House ke bahar aag lag gayi aur Sandeep ka takhne jal gaya, umm, aag abhi bhi jal rahi hai. Ambulance bula li hai.

### clip_20  (hinglish, STRUCTURAL)

> Main Reception ke peeche sheeshe ka darwaza toot gaya. Poore farsh par kaanch bikhra hua hai. Kisi ko chot nahi lagi.

### clip_21  (hinglish, HAZMAT)

> Do ghante pehle Warehouse ke peeche ammonia leak ho raha hai, smell bahut tez hai. Log khaans rahe hain aur aankhon mein jalan ho rahi hai. Humne area khaali karwa diya hai.

### clip_22  (hinglish, ENVIRONMENTAL)

> Haan toh, kisi ne Parking Area ke paas purane chemical waste ke drum phenk diye hain aur mitti par leak ho rahe hain, maine abhi abhi dekha.

### clip_23  (hinglish, MEDICAL)

> Abhi abhi Loading Dock ke paas forklift ne Anjali Mehta ko takkar maar di, uske takhne mein chot hai. Anjali Mehta hosh mein hai lekin khada nahi ho pa rahi. Ambulance bulayi gayi hai.

### clip_24  (hinglish, EQUIPMENT)

> Subah saadhe gyarah baje ke aas paas Bay 1 par CNC machine M-7 ke spindle mein problem hai aur kharab parts bana raha hai, umm, operator Arjun Nair ne usko band kar diya. Koi ghayal nahi hua.

### clip_25  (hinglish, HAZMAT)

> Abhi abhi Chemical Store mein drum kholte waqt Priya Sharma ne chemical ka dhuan saans mein le liya aur bahut khaans rahi hai. Drum band kar diya hai. First aid diya ya nahi, pata nahi.

### clip_26  (hinglish, SECURITY)

> Haan toh, subah saadhe gyarah baje ke aas paas koi bina access badge ke kisi employee ke peeche peeche Chemical Store mein ghus gaya. Woh lamba aadmi tha, neeli shirt mein. Security CCTV check kar rahi hai.

### clip_27  (hinglish, STRUCTURAL)

> Lift A doosri aur teesri manzil ke beech atak gayi. Lift khaali thi, andar koi nahi hai. Har floor par sign laga diya hai.

### clip_28  (hinglish, ENVIRONMENTAL)

> Dopahar sawa do baje boiler ki chimney se normal se bahut zyada kaala dhuan nikal raha hai. Abhi bhi chal raha hai. Aag nahi hai, sirf emission hai.

### clip_29  (hinglish, EQUIPMENT)

> Kal shaam ko Bay 1 mein conveyor belt CV-3 atak gaya, umm, motor se ghis-ghis ki awaaz aa rahi hai. Dheere dheere chal raha hai.

### clip_30  (hinglish, WATER)

> Haan toh, dopahar sawa do baje Pantry ke bahar pipe phat gaya hai aur paani leak ho raha hai. Paani abhi bhi leak ho raha hai. Kisi ko chot nahi lagi, chhota sa issue hai.
