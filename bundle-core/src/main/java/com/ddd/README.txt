"bundlesecurity" folder in both client and server could be the same:

BundleIDGenerator.java IS SAME, server has 1 more function than client
BundleSecurity is SIMILAR, can be superclass subclassed
SecurityExceptions.java IS SAME, only that constructors are different
SecurityUtils.java IS SAME, server has 1-2 more functions/fields 

----
The following files are 50-50, we should leave them alone for now but revisit them later to see if anything can be unified and simplified into a single file:

ClientSecurity.java
ServerSecurity.java




==

bundletransmission files are made up of the following file groups that can be unified:
ddd.model files 	EASY
ddd.util files		EASY
bundlesecurity files	MEDIUM
bundlerouting		???
applicationdatamanager	???
