# Privacy Policy for WiFi Auto Switch

**Last updated: August 13, 2026**

Code Optimization Ops ("we," "us," "our") operates the WiFi Auto Switch Android application (the "App"). This page explains what data the App accesses, how it's used, and your choices.

## Summary

WiFi Auto Switch does not collect, transmit, or sell your personal data to us or to any third party we control. Wi-Fi network names and passwords you add to the App are stored only on your device, encrypted, and are never uploaded anywhere. The only outside party that receives any data is Google AdMob, which serves the App's ads (see below).

## Data the App accesses on your device

**Wi-Fi network credentials.** When you add a network in the App, its network name (SSID) and password are stored locally on your device using Android's `EncryptedSharedPreferences`, protected by hardware-backed encryption. This data never leaves your device, is never sent to us, and is deleted immediately if you remove the network in the App or uninstall the App.

**Wi-Fi connection information.** The App reads your currently connected network's signal strength (RSSI) and, where permitted (see Location, below), its network name, in order to decide whether a stronger saved network is available. This information is used only in real time on-device to manage Wi-Fi network suggestions — it is not logged, stored long-term, or transmitted anywhere.

**Location permission.** The App requests `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` and, on supported Android versions, `NEARBY_WIFI_DEVICES`. This is required by Android's own operating system rules: reading a Wi-Fi network's name is classified by Android as location-adjacent data, so the OS requires this permission before it will disclose it to any app. **The App does not read, use, store, or transmit your GPS location or geographic position in any way.** The permission is used solely to unlock Wi-Fi network name information from the Android OS.

## Background operation

The App runs a foreground service (shown as a persistent notification while active) to continuously monitor Wi-Fi signal strength and manage network suggestions in the background. This is required for the App's core auto-switching function and can be stopped at any time from within the App.

## Advertising

The App displays ads served by Google AdMob. AdMob may collect device identifiers (such as the advertising ID), general device information, and approximate location for ad personalization and measurement, subject to Google's own privacy policy: https://policies.google.com/privacy. You can limit ad personalization through your device's Google settings (Settings → Google → Ads).

## Data sharing

We do not sell, rent, or share your data with third parties, except as described above for AdMob's ad-serving function.

## Data retention and deletion

All data the App stores (saved network credentials, preferences) lives only on your device. Uninstalling the App, or removing a saved network from within the App, permanently deletes that data. We do not retain any copy, since none is ever sent to us.

## Children's privacy

The App is not directed at children under 13, and we do not knowingly collect data from children.

## Changes to this policy

We may update this policy from time to time. Changes will be posted here with an updated "Last updated" date.

## Contact us

Questions about this policy can be sent to **codeoptimizationops@gmail.com**.
