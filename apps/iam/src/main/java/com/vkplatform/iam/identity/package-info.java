@org.springframework.modulith.ApplicationModule(
        displayName = "Passwordless identity",
        allowedDependencies = {"verification::api", "platform::web", "platform::persistence"}
)
package com.vkplatform.iam.identity;
