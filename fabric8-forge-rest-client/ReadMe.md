## Fabric8 Project Create System Tests

These system tests run inside a test fabric8 cluster and try to generate projects and assert they build correctly.

### Running the system tests locally

If you are connected to a kubernetes cluster so that you can type:

    kubectl get node
    
Then type the following to setup your local environment to connect to the current kubernetes cluster:
    

```bash
export TERM=dumb
export JENKINS_URL=`gofabric8 service jenkins -u`
export FABRIC8_FORGE_URL=`gofabric8 service fabric8-forge -u`
```

Then to run the tests you can just run this script:

```bash
./systest.sh
```

or if you build locally the code you can run

```bash
cd fabric8-forge-rest-client
mvn test -Dtest="*KT"
```

#### Running individual test cases

You can run individual test cases directly via:

```bash
cd fabric8-forge-rest-client
mvn test -Dtest=CreateMicroserviceProjectKT
```