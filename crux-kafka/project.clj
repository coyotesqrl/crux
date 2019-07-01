(defproject juxt/crux-kafka :derived-from-git
  :description "Crux Kakfa"
  :url "https://github.com/juxt/crux"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.logging "0.4.1"]

                 [juxt/crux-core :derived-from-git]
                 [juxt/crux-dev :derived-from-git]

                 [org.apache.kafka/kafka-clients "2.2.0" :scope "provided"]
                 [org.apache.kafka/kafka_2.12 "2.2.0"]

                 ;; TODO should be moved, as dev depdendencies?
                 [org.apache.zookeeper/zookeeper "3.4.14"
                  :exclusions [io.netty/netty
                               jline
                               org.apache.yetus/audience-annotations
                               org.slf4j/slf4j-log4j12
                               log4j]]]
  :profiles {:dev {:dependencies [[juxt/crux-dev :derived-from-git]]}}
  :middleware [leiningen.project-version/middleware]
  :java-source-paths ["src"])
