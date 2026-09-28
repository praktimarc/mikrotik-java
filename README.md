# mikrotik-java

A maintained fork of [GideonLeGrange/mikrotik-java](https://github.com/GideonLeGrange/mikrotik-java), a Java client library for the MikroTik RouterOS API.

This fork keeps the existing Java API and package names compatible with upstream while allowing independently maintained fixes and releases for Praktimarc's own projects. It is not an official upstream release.

## Fork status

- Upstream base version: `3.0.8`
- Current fork version: `3.0.8-praktimarc.1`
- Maven coordinates: `io.github.praktimarc:mikrotik:3.0.8-praktimarc.1`
- Java packages remain unchanged: `me.legrange.mikrotik.*`
- License: Apache License 2.0; original attribution is retained

Fork releases use the upstream version plus a Praktimarc suffix:

```text
3.0.8-praktimarc.1
3.0.8-praktimarc.2
...
```

When the fork moves to a later upstream base, the fork counter restarts, for example `3.0.9-praktimarc.1`.

The first fork release includes a fix for synchronous commands where an immediate RouterOS API error could otherwise be replaced by a later command-timeout exception.

## Getting the Praktimarc fork

Release JARs are published on the [GitHub Releases page](https://github.com/praktimarc/mikrotik-java/releases).

A release contains the main JAR, source JAR, and Javadoc JAR. GitHub Releases are the initial distribution mechanism and do **not** by themselves provide a remote Maven repository.

### Build and install locally with Maven

Clone this repository and run:

```bash
mvn clean install
```

This builds, tests, and installs the fork into the local Maven repository.

Projects on the same machine can then use:

```xml
<dependency>
  <groupId>io.github.praktimarc</groupId>
  <artifactId>mikrotik</artifactId>
  <version>3.0.8-praktimarc.1</version>
</dependency>
```

Alternatively, after downloading the binary JAR from GitHub Releases, install it into the local Maven repository with:

```bash
mvn install:install-file \
  -Dfile=mikrotik-3.0.8-praktimarc.1.jar \
  -DgroupId=io.github.praktimarc \
  -DartifactId=mikrotik \
  -Dversion=3.0.8-praktimarc.1 \
  -Dpackaging=jar
```

No Java import changes are required when switching from upstream. Existing imports such as `me.legrange.mikrotik.ApiConnection` remain valid.

## Upstream project

The original project is maintained by Gideon Le Grange at [GideonLeGrange/mikrotik-java](https://github.com/GideonLeGrange/mikrotik-java). Upstream remains the source for the original library design and public API. Suitable fixes from this fork may be contributed upstream separately from fork-specific release infrastructure.

For upstream contribution guidance, see [CONTRIBUTING.md](CONTRIBUTING.md).

# Using the API

How to use the API is best illustrated by examples.

These examples assume that the user is proficient in Java and understands the MikroTik command line syntax. The command line syntax gives an indication of what commands can be passed, but the RouterOS API used by this library does not support everything.

Some things to consider when debugging API calls are:
* The RouterOS API does not support auto-completion. You need to write out command and parameter names. For example, you can't say `/ip/hotspot/user/add name=john add=10.0.0.1`, you need to write out `address`.
* You need to quote values with spaces in. You can't say `name=Joe Blogs`, you need to use `name="Joe Blogs"`.
* Exceptions with a root cause of `ApiCommandException` are errors received from the remote RouterOS device and contain the error message received.

## Opening a connection

Here is a simple example that connects to a router and reboots it:

```java
ApiConnection con = ApiConnection.connect("10.0.1.1"); // connect to router
con.login("admin","password"); // log in to router
con.execute("/system/reboot"); // execute a command
con.close(); // disconnect
```

The example above creates an unencrypted connection using the default API port and timeout, which is useful for development and testing.

### TLS encryption

For production environments, encrypting API traffic is recommended. Pass a `SocketFactory` to control TLS socket creation:

```java
ApiConnection con = ApiConnection.connect(
    SSLSocketFactory.getDefault(),
    "10.0.1.1",
    ApiConnection.DEFAULT_TLS_PORT,
    ApiConnection.DEFAULT_CONNECTION_TIMEOUT);
```

The default SSL socket factory works when the router certificate has been added to the local key store. RouterOS also supports anonymous TLS; see the `AnonymousSocketFactory` example in the examples directory.

### Connection timeouts

By default, the API generates an exception if it cannot connect to the specified router. A connection failure can be immediate, for example when the OS returns `Connection refused`, or can last until the configured connection timeout.

Example with a two-second connection timeout:

```java
ApiConnection con = ApiConnection.connect(
    SSLSocketFactory.getDefault(),
    "10.0.1.1",
    ApiConnection.DEFAULT_TLS_PORT,
    2000);
```

### Constants

Some constants are provided in `ApiConnection`:

| Constant | Use for | Value |
| --- | --- | ---: |
| `DEFAULT_PORT` | Default TCP port for unencrypted connections | 8728 |
| `DEFAULT_TLS_PORT` | Default TCP port for encrypted connections | 8729 |
| `DEFAULT_CONNECTION_TIMEOUT` | Default connection timeout in milliseconds | 60000 |

### Try with resources

The API can be used in a try-with-resources statement to ensure that the connection is closed:

```java
try (ApiConnection con = ApiConnection.connect(
        SocketFactory.getDefault(), Config.HOST, ApiConnection.DEFAULT_PORT, 2000)) {
    con.login(Config.USERNAME, Config.PASSWORD);
    con.execute("/user/add name=eric");
}
```

In the following examples, connection, login and disconnection code is omitted. It is assumed that an `ApiConnection` has been established, `login()` has been called, and the connection is named `con`.

## Reading data

Print all interfaces:

```java
List<Map<String, String>> rs = con.execute("/interface/print");
for (Map<String, String> r : rs) {
    System.out.println(r);
}
```

Results are returned as a list of maps of string key/value pairs. For example, to print the names of all returned interfaces:

```java
for (Map<String, String> map : rs) {
    System.out.println(map.get("name"));
}
```

### Filtering results

Print all interfaces of type `vlan`:

```java
List<Map<String, String>> rs = con.execute("/interface/print where type=vlan");
```

### Selecting returned fields

Return only interface names:

```java
List<Map<String, String>> rs = con.execute("/interface/print where type=vlan return name");
```

## Writing data

Creating, modifying and deleting configuration objects is supported.

### Creating an object

```java
con.execute("/interface/gre/add remote-address=192.168.1.1 name=gre1 keepalive=10");
```

### Modifying an existing object

```java
con.execute("/interface/gre/set .id=gre1 remote-address=10.0.1.1");
```

### Removing an existing object

```java
con.execute("/interface/gre/remove .id=gre1");
```

### Un-setting a variable on an object

Use the `value-name` parameter. For example, assuming a rule can be accessed as `.id=*1`:

```java
con.execute("/ip/firewall/filter/unset .id=*1 value-name=time");
```

## Asynchronous commands

Commands can run asynchronously with a `ResultListener`:

```java
String tag = con.execute("/interface wireless monitor .id=wlan1 return signal-to-noise",
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
    });
```

`ResultListener` provides three callbacks:
* `receive()` receives results produced by the router.
* `error()` is called when an exception is raised, including a RouterOS trap or connection problem.
* `completed()` is called when the router indicates that the command has completed or has been cancelled.

Cancel the command by its returned tag:

```java
con.cancel(tag);
```

## Command timeouts

Command timeouts ensure that synchronous commands either return or fail within a configured time. They are separate from the connection timeout used in `connect()` and can be set with `setTimeout()`:

```java
ApiConnection con = ApiConnection.connect("10.0.1.1");
con.setTimeout(5000);
con.login("admin", "password");
con.execute("/system/reboot");
```

Command timeouts can be set before `login()` and therefore also affect login behavior. The default command timeout is 60 seconds.

# References

The RouterOS API is documented in the [MikroTik RouterOS API documentation](https://help.mikrotik.com/docs/spaces/ROS/pages/47579160/API).

# License

This library is released under the Apache License 2.0. See [LICENCE.md](LICENCE.md).
