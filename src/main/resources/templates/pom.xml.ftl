<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>[=options.springBootVersion]</version>
    <relativePath/>
  </parent>

  <groupId>[=(options.groupId?has_content)?then(options.groupId, basePackage)]</groupId>
  <artifactId>[=api.artifactId]</artifactId>
  <version>[=api.projectVersion]</version>
  <name>[=api.title?xml]</name>
  <description>[=generatedNote?xml]</description>

  <properties>
    <java.version>[=options.javaVersion]</java.version>
  </properties>

<#if checks.modulithVersion?has_content>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-bom</artifactId>
        <version>[=checks.modulithVersion]</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

</#if>
  <dependencies>
<#list build.dependencies as d>
<#if d.hasComment()>
    <!-- [=d.comment] -->
<#else>
    <dependency>
      <groupId>[=d.groupId]</groupId>
      <artifactId>[=d.artifactId]</artifactId>
<#if d.version?has_content>
      <version>[=d.version]</version>
</#if>
<#if d.scope != "compile">
      <scope>[=d.scope]</scope>
</#if>
<#if d.optional>
      <optional>true</optional>
</#if>
    </dependency>
</#if>
</#list>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
<#if build.lombok && build.bootMajor lt 4>
        <configuration>
          <excludes>
            <exclude>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </exclude>
          </excludes>
        </configuration>
</#if>
      </plugin>
<#if build.lombok && build.bootMajor gte 4>
      <!-- Lombok must be declared as an annotation processor on current JDKs -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <executions>
          <execution>
            <id>default-compile</id>
            <phase>compile</phase>
            <goals>
              <goal>compile</goal>
            </goals>
            <configuration>
              <annotationProcessorPaths>
                <path>
                  <groupId>org.projectlombok</groupId>
                  <artifactId>lombok</artifactId>
                </path>
              </annotationProcessorPaths>
            </configuration>
          </execution>
          <execution>
            <id>default-testCompile</id>
            <phase>test-compile</phase>
            <goals>
              <goal>testCompile</goal>
            </goals>
            <configuration>
              <annotationProcessorPaths>
                <path>
                  <groupId>org.projectlombok</groupId>
                  <artifactId>lombok</artifactId>
                </path>
              </annotationProcessorPaths>
            </configuration>
          </execution>
        </executions>
      </plugin>
</#if>
    </plugins>
  </build>
</project>
