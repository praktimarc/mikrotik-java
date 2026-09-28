# mikrotik-java

A maintained fork of [GideonLeGrange/mikrotik-java](https://github.com/GideonLeGrange/mikrotik-java), a Java client library for the MikroTik RouterOS API.

This fork keeps the existing Java API and package names compatible with upstream while allowing independently maintained fixes and releases for Praktimarc's own projects. It is not an official upstream release.

## Fork status

![Java CI with Maven](https://github.com/praktimarc/mikrotik-java/actions/workflows/maven.yml/badge.svg)

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

These examples should illustrate how to use this library. Please note that I assume that the user is proficient in Java and understands the Mikrotik command line syntax. The command line syntax gives you an indication of what commands you can pass, but the RouterOS API used by this library does not support everyting. 

Some things to consider when debugging your API calls are:
* The RouterOS API does not support auto-completion. You need to write out command and parameter names. For example, you can't say `/ip/hotspot/user/add name=john add=10.0.0.1`, you need to write out `address`.
* You need to quote values with spaces in. You can't say `name=Joe Blogs`, you need to use `name="Joe Blogs"`
* Exceptions with a root cause of `ApiCommandException` are errors received from the remote RouterOS device and contain the error message received. 

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

And now remove the object:

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

The `ResultListener` interface has three methods the user needs to implement:
* `receive()` is called to receive results produced by the router from the API. 
* `error()` is called when an exception is raised based on a 'trap' received from the router or another (typically connection) problem.
* `completed()` is called when the router has indicated that the command has completed or has been cancelled. 

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

The RouterOS API is documented here: http://wiki.mikrotik.com/wiki/Manual:API

# Licence

This library is released under the Apache 2.0 licence. See the [LICENCE.md](LICENCE.md) file
