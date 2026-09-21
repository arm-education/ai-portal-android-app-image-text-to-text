plugins {
    id("com.android.application")
}

android {
    namespace = "org.arm.learningpath.visionchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.arm.learningpath.visionchat"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cFlags += listOf("-O3")
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "ChromeOsAbiSupport"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {}
