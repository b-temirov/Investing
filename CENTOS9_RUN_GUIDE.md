# CentOS Stream 9 End-User Setup and Run Guide

This guide explains how to take this project from GitHub, prepare a CentOS Stream 9 machine, install the required software, and run the crawler successfully.

The project is a Java 17 Maven application that:

- opens the Investing.com dividend page
- uses `Jsoup` to parse HTML
- uses Selenium with headless Chromium when the page requires browser rendering
- finds the dividend table
- saves the table to `dividends.csv`

This guide assumes:

- you have a CentOS Stream 9 machine
- you can use `sudo`
- you can access the internet from that machine
- you will clone the project from GitHub

## 1. What must exist on the CentOS machine

Before the project can run, the machine needs:

- `git`
- Java 17 JDK
- Maven
- Chromium browser
- ChromeDriver

Why each one is needed:

- `git` is used to clone the repository from GitHub.
- Java 17 is required because the project is configured to compile and run on Java 17.
- Maven downloads dependencies and runs the project.
- Chromium is the browser Selenium will control in headless mode.
- ChromeDriver is the bridge between Selenium and Chromium.

## 2. Log into the CentOS Stream 9 machine

Open a terminal on the CentOS machine directly, or connect over SSH.

Example:

```bash
ssh your-user@your-centos-host
```

## 3. Update package metadata

It is good practice to refresh package metadata first.

```bash
sudo dnf makecache
```

If you want to update installed packages as well, you can run:

```bash
sudo dnf upgrade -y
```

This is optional, but it can reduce package mismatch issues.

## 4. Install base tools

Install Git, Java 17, Maven, and DNF plugin support:

```bash
sudo dnf install -y git java-17-openjdk-devel maven dnf-plugins-core
```

What these packages do:

- `git`: version control client used for cloning the project
- `java-17-openjdk-devel`: Java 17 JDK, including compiler tools
- `maven`: build tool used by the project
- `dnf-plugins-core`: needed for repository management commands such as enabling `crb`

## 5. Enable repositories needed for Chromium packages

On CentOS Stream 9, Chromium-related packages are commonly provided through EPEL.

First enable `crb`:

```bash
sudo dnf config-manager --set-enabled crb
```

Then install EPEL repositories:

```bash
sudo dnf install -y epel-release epel-next-release
```

Why this is necessary:

- some required browser packages are not always available in the default CentOS repositories
- EPEL extends package availability for Enterprise Linux systems

## 6. Install Chromium and ChromeDriver

Install both packages:

```bash
sudo dnf install -y chromium chromedriver
```

This is the recommended setup for this project because the Java code is already configured to run Selenium against Chromium in headless mode.

## 7. Verify that Java, Maven, Chromium, and ChromeDriver are installed

Run these commands:

```bash
java -version
mvn -version
chromium --version
chromedriver --version
```

If `chromium --version` fails, try:

```bash
chromium-browser --version
```

Some Linux systems expose the browser binary as `chromium`, others as `chromium-browser`.

What you want to confirm:

- Java reports version 17
- Maven runs successfully
- Chromium exists
- ChromeDriver exists

## 8. Clone the project from GitHub

Choose a folder where you want the project to live, then clone it.

Example:

```bash
git clone https://github.com/your-user/your-repo.git
cd your-repo
```

Replace:

- `your-user` with your GitHub username or organization
- `your-repo` with the repository name

After cloning, verify that files such as `pom.xml` and `src/` are present:

```bash
ls
```

You should see at least:

- `pom.xml`
- `src`

## 9. Set the environment variables expected by the project

The Java code tries to locate the Chromium browser automatically from common Linux paths, but it is better to set the path explicitly so the runtime behavior is predictable.

The code also supports an explicit ChromeDriver path through an environment variable.

### Option A: if the Chromium binary is `/usr/bin/chromium`

Run:

```bash
export CHROMIUM_BINARY=/usr/bin/chromium
export CHROMEDRIVER_PATH=/usr/bin/chromedriver
```

### Option B: if the Chromium binary is `/usr/bin/chromium-browser`

Run:

```bash
export CHROMIUM_BINARY=/usr/bin/chromium-browser
export CHROMEDRIVER_PATH=/usr/bin/chromedriver
```

### How to determine which one is correct

You can check with:

```bash
which chromium
which chromium-browser
which chromedriver
```

Whichever browser path exists should be used for `CHROMIUM_BINARY`.

## 10. Make the environment variables persistent

If you do not want to retype the variables every time, add them to your shell startup file.

If you use Bash:

```bash
echo 'export CHROMIUM_BINARY=/usr/bin/chromium' >> ~/.bashrc
echo 'export CHROMEDRIVER_PATH=/usr/bin/chromedriver' >> ~/.bashrc
source ~/.bashrc
```

If your system uses `chromium-browser` instead, replace the first line with:

```bash
echo 'export CHROMIUM_BINARY=/usr/bin/chromium-browser' >> ~/.bashrc
```

If you use Zsh:

```bash
echo 'export CHROMIUM_BINARY=/usr/bin/chromium' >> ~/.zshrc
echo 'export CHROMEDRIVER_PATH=/usr/bin/chromedriver' >> ~/.zshrc
source ~/.zshrc
```

Again, replace `/usr/bin/chromium` with `/usr/bin/chromium-browser` if that is the actual binary path.

## 11. Build the project

Move into the cloned project directory and run:

```bash
mvn clean compile
```

What this does:

- downloads Java dependencies defined in `pom.xml`
- compiles the Java source code
- verifies the project builds successfully

Expected result:

- Maven finishes with `BUILD SUCCESS`

If you are running this for the first time, Maven may take longer because it needs to download dependencies from Maven Central.

## 12. Run the project

Once compilation succeeds, run:

```bash
mvn exec:java
```

What happens when you run this:

- Maven starts the configured Java class
- the crawler fetches the target page
- if needed, Selenium launches headless Chromium
- the crawler scrolls until it finds the dividend table
- the table is converted to CSV
- the CSV is saved as `dividends.csv` in the project directory

Expected console output should include a message similar to:

```text
Saved CSV to /full/path/to/your/project/dividends.csv
```

## 13. Confirm that the CSV file was created

Check whether the output file exists:

```bash
ls -l dividends.csv
```

You can view the first few lines with:

```bash
head dividends.csv
```

Or open the whole file:

```bash
cat dividends.csv
```

## 14. Full example from a fresh machine

If you want the whole sequence in one place, this is the typical order:

```bash
sudo dnf makecache
sudo dnf install -y git java-17-openjdk-devel maven dnf-plugins-core
sudo dnf config-manager --set-enabled crb
sudo dnf install -y epel-release epel-next-release
sudo dnf install -y chromium chromedriver

java -version
mvn -version
chromium --version
chromedriver --version

git clone https://github.com/your-user/your-repo.git
cd your-repo

export CHROMIUM_BINARY=/usr/bin/chromium
export CHROMEDRIVER_PATH=/usr/bin/chromedriver

mvn clean compile
mvn exec:java
ls -l dividends.csv
```

If the Chromium binary on your machine is `chromium-browser`, replace the `CHROMIUM_BINARY` value accordingly.

## 15. How the driver is found in this project

This project supports two ways of finding ChromeDriver:

### Method 1: explicit driver path

If `CHROMEDRIVER_PATH` is set, the application tells Selenium exactly where the driver binary is.

This is the most predictable setup.

### Method 2: Selenium automatic driver resolution

If `CHROMEDRIVER_PATH` is not set, Selenium may try to resolve the driver automatically.

You should not depend on that on a fresh CentOS machine unless you have already confirmed it works in your environment.

Reasons it may fail:

- no outbound network access
- driver download restrictions
- browser/driver version mismatch
- OS library issues

For that reason, this guide recommends explicitly installing `chromedriver` and setting `CHROMEDRIVER_PATH`.

## 16. Troubleshooting

### Problem: `No Chromium/Chrome binary found`

Meaning:

- the application could not find the browser executable

Fix:

1. verify Chromium is installed
2. run `which chromium`
3. run `which chromium-browser`
4. export `CHROMIUM_BINARY` using the correct path

Example:

```bash
export CHROMIUM_BINARY=/usr/bin/chromium
```

### Problem: ChromeDriver session creation fails

Typical symptoms:

- `session not created`
- driver mismatch errors

Meaning:

- Chromium and ChromeDriver may be incompatible

Fix:

1. check both versions:

```bash
chromium --version
chromedriver --version
```

2. update packages:

```bash
sudo dnf upgrade -y chromium chromedriver
```

3. ensure `CHROMEDRIVER_PATH` points to the installed driver

### Problem: Maven cannot download dependencies

Meaning:

- the machine cannot reach Maven Central or has network restrictions

Fix:

1. verify network connectivity
2. verify DNS works
3. if your environment uses a proxy, configure Maven for that proxy

### Problem: Build succeeds but no CSV is created

Meaning:

- the application may have failed during runtime
- the page structure may have changed
- anti-bot behavior may be blocking the crawler

Fix:

1. review the console output for the exception
2. increase waiting or scrolling logic if needed
3. verify the target page still uses the expected table structure

### Problem: Cookie popup blocks interaction

Meaning:

- the site may be showing a different cookie consent layout than the code expects

Fix:

1. inspect the page on a normal desktop browser
2. update the Selenium cookie button selectors in the Java code if the page changed

## 17. Where the output is stored

The file is written to:

```text
dividends.csv
```

This is relative to the directory where you run Maven. In normal usage, that means the CSV ends up in the project root.

## 18. Recommended operational approach

For a stable CentOS Stream 9 deployment, use this approach every time:

1. install Chromium
2. install ChromeDriver
3. set `CHROMIUM_BINARY`
4. set `CHROMEDRIVER_PATH`
5. run `mvn clean compile`
6. run `mvn exec:java`

This is more reliable than hoping Selenium auto-resolves everything on a new machine.

## 19. Summary

If you clone this repository onto a brand-new CentOS Stream 9 machine, the project source is ready, but the machine is not ready until you install the runtime tools.

The minimum practical checklist is:

- Java 17 installed
- Maven installed
- Chromium installed
- ChromeDriver installed
- `CHROMIUM_BINARY` set correctly
- `CHROMEDRIVER_PATH` set correctly

Once that is done, the normal commands are:

```bash
mvn clean compile
mvn exec:java
```

The expected output is a generated file named `dividends.csv`.
