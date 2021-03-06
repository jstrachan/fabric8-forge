## Fabric8 Forge

This project contains the Fabric8 extensions for [JBoss Forge](http://forge.jboss.org/). It includes:

* [addons](addons) are various Forge addons for working with Apache Camel and [Fabric8 DevOps](http://fabric8.io/guide/fabric8DevOps.html)
* rest provides a REST service for working with JBoss Forge with [Fabric8 DevOps](http://fabric8.io/guide/fabric8DevOps.html) inside the [Fabric8 Console](http://fabric8.io/guide/console.html)


### Documentation

For more details see the [Fabric8 Forge Documentation](http://fabric8.io/guide/forge.html)

### Building the addons

To try out addons:

    cd addons
    mvn install
    
Then you can install the addons into forge via the [forge addon-install command](http://fabric8.io/guide/forge.html) using the current snapshot build version 
    
### Building the REST service

Build everything via:

    mvn install
    
The test case in the [fabric8-forge](fabric8-forge) module takes a while to build as it pre-populates the local maven repository with all the required jars for the Forge tooling.
    
So you might want to only include tests in the [fabric8-forge](fabric8-forge) module the first build of the day.
    

Then to build and deploy the docker image:
    
    cd fabric8-forge
    mvn docker:build fabric8:apply            