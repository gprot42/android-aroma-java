
AROMA (Android Remote Online Management App)

To enable accessibility over mobile networks (cellular data), the local HTTP server needs to be exposed to the internet via a tunneling service, as mobile carriers typically use NAT and block incoming connections. We'll integrate the java-ngrok library (a Java wrapper for ngrok) to create a public tunnel to the local server. This allows remote access from any browser via a public URL, regardless of whether the device is on Wi-Fi or mobile data.

Ngrok requires a free account for an authtoken (sign up at https://ngrok.com). Free tunnels use random URLs that change on restart; for static URLs or advanced features, upgrade to a paid plan.


