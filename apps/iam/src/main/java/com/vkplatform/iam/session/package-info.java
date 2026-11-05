@org.springframework.modulith.ApplicationModule(
        displayName = "Authentication sessions",
        allowedDependencies = {"identity::api", "platform::web", "platform::persistence"}
)
package com.vkplatform.iam.session;
