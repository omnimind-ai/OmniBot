# host.json is an external contract parsed through Gson reflection. These DTOs
# must remain instantiable with their original field names in minified hosts.
-keep class cn.com.omnimind.bot.omniflow.OmniFlowRuntimeManifest { *; }
-keep class cn.com.omnimind.bot.omniflow.RuntimeTool { *; }
