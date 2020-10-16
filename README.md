# Proto-Zabbix

Explore Zabbix Protocols using Clojure

[Leiningen](https://github.com/technomancy/leiningen) dependency
information for the latest version at Clojars:

![](https://clojars.org/f0bec0d/proto-zabbix/latest-version.svg)

## Installation

Download from https://github.com/alexei-matveev/proto-zabbix

## Usage

To start a zabbix server at the default port issue

    $ lein run

For zabbix-get, zabbix-sender and zabbix-agentd see code.

    $ lein uberjar
    $ java -jar proto-zabbix-XYZ.jar [args]

## Deploy Artifacts

    $ lein deploy clojars

And supply username and a Deploy Token as a password. The Deploy Token
should allow writing to the
[Repo](https://clojars.org/f0bec0d/proto-zabbix).

## License

Copyright © 2015 Alexei Matveev <alexei.matveev@gmail.com>

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
