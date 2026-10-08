# mikrotik-java

A maintained fork of [GideonLeGrange/mikrotik-java](https://github.com/GideonLeGrange/mikrotik-java), a Java client library for the MikroTik RouterOS API.

This fork keeps the existing Java API and package names compatible with upstream while allowing independently maintained fixes and releases for Praktimarc's own projects. It is not an official upstream release.

## Fork status

![Java CI with Maven](https://github.com/praktimarc/mikrotik-java/actions/workflows/maven.yml/badge.svg)

- Upstream base version: `3.0.8`
- Current fork version: `3.0.8-praktimarc.4`
- Maven coordinates: `io.github.praktimarc:mikrotik:3.0.8-praktimarc.4`
- Java packages remain unchanged: `me.legrange.mikrotik.*`
- Java baseline: Java 11; builds require JDK 11 or newer
- License: Apache License 2.0; original attribution is retained
- Detailed release notes: [v3.0.8-praktimarc.4](docs/releases/v3.0.8-praktimarc.4.md)

Fork versions use the upstream version plus a Praktimarc suffix:

```text
3.0.8-praktimarc.1
3.0.8-praktimarc.2
3.0.8-praktimarc.3
3.0.8-praktimarc.4
...
```

When the fork moves to a later upstream base, the fork counter restarts, for example `3.0.9-praktimarc.1`.

`3.0.8-praktimarc.1` was the first published fork release and fixed synchronous commands where an immediate RouterOS API error could otherwise be replaced by a later command-timeout exception. `3.0.8-praktimarc.2` existed only as an intermediate source version and was never tagged or published; it introduced the binary-safe RouterOS file-download work. `3.0.8-praktimarc.3` is the first published release after `.1` and includes both that unpublished `.2` work and the transport, lifecycle, protocol, concurrency, and public-exception hardening documented below. `3.0.8-praktimarc.4` is a focused follow-up that exposes terminal RouterOS `!done` properties through the public listener API while preserving the existing synchronous and legacy-listener behavior.

## Getting the Praktimarc fork

The main JAR, source JAR, and Javadoc JAR for `3.0.8-praktimarc.4` are already available on the [GitHub Releases page](https://github.com/praktimarc/mikrotik-java/releases). Maven Central publishing is being prepared. Until it has been externally verified, do not assume this version can already be resolved from Maven Central.

Once this release is published to Maven Central, consumers can use its normal Maven coordinates without adding any custom repository, authentication, or locally installed JAR:

```xml
<dependency>
  <groupId>io.github.praktimarc</groupId>
  <artifactId>mikrotik</artifactId>
  <version>3.0.8-praktimarc.4</version>
</dependency>
```

For contributors, Java 11 or newer and Maven are required. This build targets Java 11 bytecode:

```bash
mvn clean verify
```

No import changes are required compared to upstream. Existing packages such as `me.legrange.mikrotik.ApiConnection` remain unchanged.

See [Maven Central publishing](docs/publishing.md) for prerequisites, signed staging, approval gates, and consumer verification.

## Binary-safe file downloads

The unpublished `3.0.8-praktimarc.2` source line introduced `ApiConnection.downloadFile()` for downloading arbitrary RouterOS files without converting the file payload to text. This capability is first published in `3.0.8-praktimarc.3`. The implementation requires RouterOS 7.13 or newer and uses `/file/read` with chunks of at most 32768 bytes over the already authenticated native API connection.

```java
long bytes = con.downloadFile(
        "flash/docsis/cm123456.cfg",
        Path.of("/srv/docsis/cm123456.cfg"));
```

The returned `long` is the completed byte count. Binary payload bytes are kept out of the text-oriented `String` result path, so NUL bytes, invalid UTF-8 sequences, and arbitrary compiled data are preserved unchanged.

The binary-safe path has also been validated against a real RouterOS device with both a small configuration file and an approximately 130 kB binary file.

Before transfer, an existing final target and stale sibling `.part` file are removed. The download is written only to the `.part` file, its final byte count is validated, and only then is it moved to the requested target name. If the transfer or validation fails, the final target and `.part` file are removed best-effort and the operation reports failure instead of publishing stale or partial data.

The existing `execute()` methods remain text-oriented and keep their existing `Map<String, String>` behavior. Binary upload and a RouterOS pre-7.13 small-file fallback are not included in this release.

## Connection concurrency and failure handling

`3.0.8-praktimarc.3` hardens one `ApiConnection` for concurrent use without introducing a second public dispatcher or command-handle API.

Multiple synchronous, asynchronous, and binary file-read operations may be active on one connection at the same time. Complete RouterOS command sentences are serialized internally on the shared output stream, so words from two commands cannot interleave. The lock covers only the command write and fatal send transition; it is not held while waiting for RouterOS replies.

Command registrations are terminal and cleaned up before terminal callbacks. `!done` completes a command, while `!trap` and the retained legacy `!halt` compatibility path fail that command. A local synchronous timeout removes its registration but does not automatically issue RouterOS `/cancel`.

Fatal EOF/socket loss, unrecoverable protocol/framing errors, RouterOS `!fatal`, and fatal send failures terminate the complete session and fail all still-active operations with `ApiConnectionException`. A send-side `IOException` marks the connection failed before the internal write lock is released, so a writer already waiting for that lock cannot write another command onto the broken session.

`close()` is idempotent in the built-in implementation. Intentional close terminates active operations but is distinct from unexpected fatal loss and therefore does not trigger `ConnectionListener`.

### Connection loss notification

Applications that need to observe an unexpected session loss even when no command is active can register a `ConnectionListener`:

```java
con.addConnectionListener(cause ->
        System.err.println("RouterOS API connection lost: " + cause.getMessage()));
```

A listener registered after the connection has already entered its fatal failed state is notified immediately with the retained failure. Duplicate registration of the same listener instance does not duplicate the notification, and removal is idempotent.

There is no automatic reconnect, command replay, or transparent session replacement in this low-level library.

### Public exception types

Consumers should catch the stable public package types rather than compile against `me.legrange.mikrotik.impl.*`:

```java
try {
    con.execute("/system/resource/print");
} catch (ApiCommandException ex) {
    System.err.println("RouterOS rejected tag " + ex.getTag()
            + ": " + ex.getMessage());
} catch (ApiDataException ex) {
    System.err.println("RouterOS API data could not be interpreted: " + ex.getMessage());
} catch (ApiConnectionException ex) {
    System.err.println("RouterOS API connection failed: " + ex.getMessage());
}
```

- `ApiConnectionException` represents connection, transport, or session-fatal failure.
- `ApiCommandException` represents a RouterOS command error and exposes tag/category metadata.
- `ApiDataException` represents malformed or inconsistent API data when the failure can be scoped without losing session routing.

`ApiCommandException.hasCategory()` distinguishes a real RouterOS category `0` from an omitted category; `getCategory()` remains integer-compatible and returns `0` when no category was supplied.

### RouterOS reply vocabulary

The dispatcher recognizes the current native API reply words used by this fork:

- `!re` — command data;
- `!empty` — valid no-data response introduced by RouterOS 7.18; it is non-terminal and the command remains active until `!done`;
- `!done` — normal command completion;
- `!trap` — RouterOS command error;
- `!halt` — legacy/compatibility handling equivalent to a command error;
- `!fatal` — fatal session error, including best-effort extraction of free-word diagnostics.

Unknown reply words, duplicate/untrustworthy tag routing, reserved/unsupported control bytes, truncated framing, and other unrecoverable protocol states fail the connection instead of being silently ignored. A malformed but uniquely tagged command reply can be failed locally with `ApiDataException` when routing remains trustworthy.

### Terminal completion metadata

`3.0.8-praktimarc.4` makes terminal RouterOS `!done` properties available to callers using `execute(String, ResultListener)`. `ResultListener` keeps its existing `completed()` method and adds a backward-compatible default overload:

```java
default void completed(Map<String, String> completion) {
    completed();
}
```

Existing listener implementations therefore continue to work without changes. Listeners that need terminal metadata can override `completed(Map<String, String>)` in addition to the existing required methods.

The completion map contains all normal `=name=value` properties from `!done`, including `ret` and any other RouterOS-supplied terminal values. `.tag` remains internal response-routing metadata and is not included. A plain `!done` produces an empty map, and the map delivered to the listener is unmodifiable.

The synchronous `execute(String)` API intentionally keeps its established result shape: a terminal `ret` is still exposed as an additional result containing only `ret`, while other completion properties are not injected into the legacy synchronous result list. This lets higher-level APIs use the listener path for both sync and async commands without changing existing low-level synchronous callers.

## Upstream project

The original project is maintained by Gideon Le Grange at [GideonLeGrange/mikrotik-java](https://github.com/GideonLeGrange/mikrotik-java). Upstream remains the source for the original library design and public API. Suitable fixes from this fork may be contributed upstream separately from fork-specific release infrastructure.

For upstream contribution guidance, see [CONTRIBUTING.md](CONTRIBUTING.md).

# Using the API

How to use the API is best illustrated by examples. 

These examples should illustrate how to use this library. Please note that I assume that the user is proficient in Java and understands the Mikrotik command line syntax. The command line syntax gives you an indication of what commands you can pass, but the RouterOS API used by this library does not support everyting. 

Some things to consider when debugging your API calls are:
* The RouterOS API does not support auto-completion. You need to write out command and parameter names. For example, you can't say `/ip/hotspot/user/add name=john add=10.0.0.1`, you need to write out `address`.
* You need to quote values with spaces in. You can't say `name=Joe Blogs`, you need to use `name="Joe Blogs"`
* RouterOS command errors are exposed directly as the public `ApiCommandException`; connection and malformed-data failures use `ApiConnectionException` and `ApiDataException` respectively.

## Opening a connection
Here is a simple example: Connect to a router and reboot it. 

```java
ApiConnection con = ApiConnection.connect("10.0.1.1"); // connect to router
con.login("admin","password"); // log in to router
con.execute("/system/reboot"); // execute a command
con.close(); // disconnect from router
```
The above example shows a easy way of creating an unencrypted connection using the default API port and timeout, which is useful for development and testing.

### TLS encryption

For production environments, encrypting API traffic is recommended. To do this you need to open a TLS connection to the router by passing an instance of the `SocketFactory` you wish to use to construct the TLS socket to the API:

```java
ApiConnection con = ApiConnection.connect(SSLSocketFactory.getDefault(), "10.0.1.1", ApiConnection.DEFAULT_TLS_PORT, ApiConnection.DEFAULT_CONNECTION_TIMEOUT);
```

Above an instance of the default SSL socket factory is passed to the API. This will work as long as the router's certificate has been added to the local key store.  Besides allowing the user to specify the socket factory, the above method also gives full control over the TCP Port and connection timeout. 

RouterOS also supports anonymous TLS. An example showing how to create a socket factory for anonymous TLS is `AnonymousSocketFactory` in the examples directory.

### Connection timeouts

By default, the API will generate an exception if it cannot connect to the specified router. This can take place immediately (typically if the OS returns a 'Connection refused' error), but can also take up to 60 seconds if the router host is firewalled or if there are other network problems. This 60 seconds is the 'default connection timeout' an can be overridded by passing the preferred timeout to the APi as last parameter in a ```connect()``` call. For example:

```java
   ApiConnection con = ApiConnection.connect(SSLSocketFactory.getDefault(), "10.0.1.1", ApiConnection.DEFAULT_TLS_PORT, 2000); // connect to router on the default API port and fail in 2 seconds
```	

### Constants
Some constants are provided in `ApiConnection` to make it easier for users to construct connections with default ports and timeouts:

Constant | Use for | Value 
---------|---------|------
DEFAULT_PORT | Default TCP `port` value for unencrypyted connections | 8728
DEFAULT_TLS_PORT | Default TCP `port` value for encrypyted connections | 8729
DEFAULT_CONNECTION_TIMEOUT | Default connection `timeout` value (ms) | 60000

### Try with resources 

The API can also be used in a "try with resources" statement which will ensure that the connection is closed:

```java
        try (ApiConnection con = ApiConnection.connect(SocketFactory.getDefault(), Config.HOST, ApiConnection.DEFAULT_PORT, 2000)) {
            con.login(Config.USERNAME, Config.PASSWORD);
            con.execute("/user/add name=eric");
        }
```

In following examples the connection, login and disconnection code will not be repeated. In all cases it is assumed that an `ApiConnection` has been established, `login()` has been called, and that the connection is called `con`.

## Reading data 

A simple example that returns a result - Print all interfaces:


```java
List<Map<String, String>> rs = con.execute("/interface/print");
for (Map<String,String> r : rs) {
  System.out.println(r);
}
```

Results are returned as a list of maps of String key/value pairs. The reason for this is that a command can return multiple results, which have multpile variables. For example, to print the names of all the interfaces returned in the command above, do:

```java
for (Map<String, String> map : rs) { 
  System.out.println(map.get("name"));
}
```

### Filtering results

The same query, but with the results filtered: Print all interfaces of type 'vlan'.

```java
List<Map<String, String>> rs = con.execute("/interface/print where type=vlan");
```

### Selecting returned fields

The same query, but we only want certain result fields names: Print all interfaces of type 'vlan' and return just their name:

```java
List<Map<String, String>> rs = con.execute("/interface/print where type=vlan return name");
```

## Writing data 

Creating, modifying and deleting configuration objects is of course possible.

### Creating an object 

This example shows how to create a new GRE interface: 

```java
con.execute("/interface/gre/add remote-address=192.168.1.1 name=gre1 keepalive=10");
```

### Modify an existing object

Change the IP address in the object created by the above example:

```java
con.execute("/interface/gre/set .id=gre1 remote-address=10.0.1.1"); 
```

### Remove an existing object

And now remove it:

```java
con.execute("/interface/gre/remove .id=gre1"); 
```

### Un-setting a variable on an object 

Un-setting a variable is a bit different, and you need to use a parameter called `value-name`. This isn't well documented. Let's say you have a firewall rule that was set up like this:

```java
con.execute("/ip/firewall/filter/add action=accept chain=forward time=00:00:01-01,mon")
```
Assuming the rule can be accessed as `.id=*1`, you un-set it by using `value-name` as seen below:

```java 
con.execute("/ip/firewall/filter/unset .id=*1 value-name=time");
```

## Asynchronous commands

We can run some commands asynchronously in order to continue receiving updates:

This example shows how to run '/interface wireless monitor' and have the result sent to a listener object, which prints it:

```java
String tag = con.execute("/interface/wireless/monitor .id=wlan1 return signal-to-noise", 
      new ResultListener() {

            public void receive(Map<String, String> result) {
                System.out.println(result);
            }

           public void error(MikrotikApiException e) {
               System.out.println("An error occurred: " + e.getMessage());
           }

           public void completed() {
                System.out.println("Asynchronous command has finished"); 
           }
            
        }
  );
```

The `ResultListener` interface retains three methods that existing implementations already provide:
* `receive()` is called to receive results produced by the router from the API.
* `error()` is called when an exception is raised based on a RouterOS command error or another API failure.
* `completed()` is called when the router has indicated that the command has completed or has been cancelled.

Starting with `3.0.8-praktimarc.4`, listeners may additionally override the default `completed(Map<String, String> completion)` method when they need properties carried by the terminal `!done` sentence. Existing implementations that only implement `completed()` continue to work unchanged because the default metadata callback delegates to it.

The above command will run and send results asynchronously as they become available, until it is canceled. The command (identified by the unique String returned) is canceled like this:

```java
con.cancel(tag);
```

## Command timeouts

Command timeouts can be used to make sure that synchronous commands either return or fail within a specific time. Command timeouts are separate from the connection timeout used in ```connect()```, and can be set using ```setTimeout()```. Here is an example:

```java
ApiConnection con = ApiConnection.connect("10.0.1.1"); // connect to router
con.setTimeout(5000); // set command timeout to 5 seconds
con.login("admin","password"); // log in to router
con.execute("/system/reboot"); // execute a command
``` 
 	
It is important to note that command timeouts can be set before ```login()``` is called, and can therefore influence the behaviour of login. 

The default command timeout, if none is set by the user, is 60 seconds. 

# References

The RouterOS native API is documented by MikroTik at https://help.mikrotik.com/docs/spaces/ROS/pages/47579160/API .

# Licence

This library is released under the Apache 2.0 licence. See the [LICENCE.md](LICENCE.md) file
