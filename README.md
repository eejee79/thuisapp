# Werkplek – Home Assistant + Proxmox + Fritzbox

Eigen Android-app met vier tabbladen onderin:

- **Dashboard**: thermostaat (schuif + aan/uit), verwarming werkplek (schuif + aan/uit), receiver
  (aan/uit, bronkeuze, volume), stroom nu (P1), crypto (BTC-saldo in euro via CoinGecko),
  stroomprijs nu + komende 3 uur (Zonneplan) en laadlimiet Škoda Elroq (schuif).

- **Home Assistant**: vier schakelaars – Eettafel (4 lampen), Werkplek (3), Slaapkamer (2) en
  Woonkamer (dressoir, 3 hanglampen, 2 TV-lampen). Aan = alle lampen van de groep aan, uit = alle uit.
  Lang indrukken toont de lampen met hun entity-ID.
- **Proxmox**: bovenaan de host (mini-pc) met CPU-temperatuur, NVMe-temperatuur, CPU en RAM;
  daaronder VM 103 met status, CPU, netwerk (↓/↑ per seconde) en geheugen,
  plus knoppen Afsluiten / Herstarten (of Starten als hij uit staat), met bevestiging.
- **Fritzbox** (TR-064): herstarten, WiFi aan/uit (2,4 + 5 GHz samen) en het kanaal
  per band kiezen. Ververst elke 15 seconden.

## Gegevens
Staan in `local.properties` (alleen op deze computer, niet delen):
`HA_URL` (extern, Nabu Casa), `HA_URL_LOCAL` (thuis, wordt eerst geprobeerd), `HA_TOKEN`, `PVE_URL`, `PVE_TOKEN_ID`, `PVE_SECRET`, `FRITZ_URL`, `FRITZ_USER`, `FRITZ_PASS`.
Later wijzigen kan ook in de app via ⚙.

## Aanpassen
In `MainActivity.kt` bovenaan:
- `LIGHT_GROUPS` – de schakelaars en hun lampen (entity-ID's), bijv. `"Keuken" to listOf("light.keuken1_light", …)`
- `DASH_…` en `BTC_AMOUNT` – de entiteiten en het bitcoinsaldo van het Dashboard
- `PVE_VMIDS` – welke VM's/containers op het Proxmox-tabblad staan, bijv. `listOf(103, 104)`

Eigen logo's voor de tabbladen: zet `ic_tab_ha.png` / `ic_tab_pve.png` / `ic_tab_fritz.png` in
`app/src/main/res/drawable/` en verwijder de gelijknamige `.xml`-bestanden.

## Proxmox-token
Rechten nodig: Datacenter → Permissions → Add → API Token Permission,
path `/`, token `root@pam!App`, rol **PVEVMUser** én (voor de hostkaart) rol **PVEAuditor**.
Temperatuur komt uit de sensors-aanpassing op de Proxmox-host (`sensorsOutput` of `thermalstate`
in `/nodes/<node>/status`).
Het zelfondertekende certificaat van Proxmox wordt bij de eerste verbinding onthouden.

## Fritzbox
- In de Fritzbox: Thuisnetwerk → Netwerk → Netwerkinstellingen → **Toegang voor toepassingen toestaan** aan.
- Gebruiker `apptest`: recht **FRITZ!Box-instellingen**, en voor gebruik via internet ook **Toegang vanaf internet toegestaan**.
- Via Cloudflare-tunnel: hostname (bijv. `fritzapi.famvegt.nl`) → service `http://<ip-fritzbox>:49000`, en `FRITZ_URL=https://fritzapi.famvegt.nl`.
- Via WireGuard/thuis: `FRITZ_URL=http://<ip-fritzbox>:49000`.

## Bouwen
Android Studio → Build → Build APK(s), of de groene ▶ met de telefoon via USB.
