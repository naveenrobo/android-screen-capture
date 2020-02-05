# Andriod-ScreenCapture
Android app for screen capturing and casting it via TCP/IP

### TCP+H264
```bash
ffplay -framerate 60 -i tcp://<your server ip here>:49152?listen
```