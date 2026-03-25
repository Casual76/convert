import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.p2r3.convert",
  appName: "Convert to it!",
  webDir: "dist",
  plugins: {
    SplashScreen: {
      launchAutoHide: false,
      backgroundColor: "#08101F",
      androidScaleType: "CENTER_CROP",
      showSpinner: false
    },
    StatusBar: {
      style: "LIGHT",
      backgroundColor: "#00000000",
      overlaysWebView: true
    }
  }
};

export default config;
