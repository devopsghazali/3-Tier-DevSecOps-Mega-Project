# DevSecOps CI/CD Pipeline Setup
## Jenkins Controller + Agent Based Architecture

---

## 📋 Purpose

This document describes a complete DevSecOps CI/CD setup using Jenkins Controller + Jenkins Agent architecture for a Node.js monorepo containing:

- `./client`
- `./api`

**Primary Focus**: Problem-driven learning
- What problems occurred
- Why they occurred (root cause)
- How they were fixed correctly (production mindset)

> **Note**: No credentials, usernames, or tokens are included.

---

## 🏗️ High-Level Architecture

Jenkins follows a **Controller–Agent model**:

| Component | Responsibilities |
|-----------|-----------------|
| **Jenkins Controller** | • Orchestrates pipelines<br>• Stores job configuration<br>• Hosts SonarQube server |
| **Jenkins Agent (agent-1)** | • Executes all pipeline stages<br>• Holds workspace<br>• Runs builds, scans, Docker, npm, etc. |

> ⚠️ **Important**: Jenkins controller does NOT build or scan code. Jenkins agent does ALL execution.

---

## 🛠️ Tools Used (Installed on Agent)

- Node.js & npm
- Docker
- Sonar Scanner (CLI)
- Gitleaks
- OWASP Dependency Check
- Trivy

> All tools are installed as **binaries**, not via Docker or npm.

---

## 📁 Repository Structure

```
repo-root/
├── client/
│   ├── package.json
│   └── Dockerfile
├── api/
│   ├── package.json
│   └── Dockerfile
└── Jenkinsfile
```

Each folder is treated as an **independent service**.

---

## 🔄 CI/CD Pipeline Flow

1. **Checkout** code from Git
2. **Secrets scan** (Gitleaks)
3. **Static code analysis** (SonarQube)
4. **Dependency vulnerability scan** (OWASP)
5. **Application build** (npm)
6. **Filesystem/image vulnerability scan** (Trivy)
7. **Docker image build**
8. **Docker image push**
9. **Quality gate enforcement**

---

## 🔐 Environment Variables

### Why Environment Variables Are Needed

Environment variables allow Jenkins to:
- Avoid hardcoding values
- Reuse configuration
- Securely inject tool configuration

**Examples**:
- SonarQube URL & token
- Runtime mode (NODE_ENV)

### Where They Are Defined

- **Jenkins UI**: Manage Jenkins → Configure System → SonarQube
- **Pipeline** `environment {}` block

> ⚠️ They are **NOT** defined inside the OS manually.

---

## 🔍 SonarQube: Server vs Scanner

### Common Confusion

> "SonarQube is running on Jenkins server, but scanner runs on agent — how?"

### Explanation

SonarQube has **two separate components**:

| Component | Purpose | Location |
|-----------|---------|----------|
| **SonarQube Server** | Stores analysis results<br>Provides UI & dashboards | Jenkins Controller |
| **Sonar Scanner** | Analyzes source code<br>Sends results to server | Jenkins Agent |

> 💡 Scanner **must run where the code exists**, i.e., the agent workspace.

---

## 🐛 Problems Faced & Fixes

### Problem 1: Missing Tools on Agent

**🔴 Problem**
```
command not found
gitleaks / trivy / dependency-check missing
```

**🔍 Root Cause**
- Jenkins agent is a clean machine
- No tools installed by default

**✅ Fix**
- Installed each tool manually as a binary
- Verified using:
```bash
tool --version
```

---

### Problem 2: Wrong Scan Path (`--source .`)

**🔴 Problem**
- Entire repo scanned instead of specific service

**🔍 Root Cause**
- Using `--source .` scans everything

**✅ Fix**
- Used Jenkins `dir()` step:
```groovy
dir('client') { ... }
dir('api') { ... }
```

---

### Problem 3: Docker Permission Denied

**🔴 Error**
```
permission denied while trying to connect to the Docker daemon socket
```

**🔍 Root Cause**
- Jenkins agent user not allowed to access Docker socket

**✅ Fix**
```bash
usermod -aG docker jenkins-agent
systemctl restart docker
```

**Verification**:
```bash
docker ps
```

---

### Problem 4: npm EACCES Permission Error

**🔴 Error**
```
npm error EACCES: permission denied, rename
```

**🔍 Root Cause**
- `node_modules` created earlier using `sudo npm install`
- Files owned by root

**✅ Fix**
```bash
rm -rf node_modules package-lock.json
```

Then allow Jenkins to run:
```bash
npm install
```

**⚡ Best Practice**: Never use `sudo npm install` in CI/CD

---

### Problem 5: Missing npm Build Script

**🔴 Error**
```
npm error Missing script: "build"
```

**🔍 Root Cause**
- `package.json` had no build script

**✅ Fix**
- Verified available scripts:
```bash
npm run
```
- Adjusted pipeline to run only valid scripts

---

### Problem 6: Trivy Showing Vulnerabilities After Image Build

**🤔 Confusion**
- Vulnerabilities appeared only after Docker image build

**📖 Explanation**

Trivy scans at multiple layers:
- Source dependencies
- OS packages inside image
- Runtime environment

Some vulnerabilities exist only inside the final image.

**✅ Correct Fix Process**

1. Fix dependency in repo
2. Commit changes
3. Jenkins rebuilds image
4. Trivy rescans new image

> ⚠️ **Important**: Docker images are immutable — old images cannot be fixed

---

### Problem 7: Jenkins Workspace & Image Rebuilds

**❓ Question**
- Does Jenkins create a new image every time?

**💡 Answer**
- Jenkins reuses workspace
- Docker uses build cache
- New image built only if something changes

This explains varying build times.

---

### Problem 8: Docker Socket Permission Still Failing After usermod

**🔴 Error (Repeated Even After Fix)**
```
permission denied while trying to connect to the Docker daemon socket
unix:///var/run/docker.sock
```

**❓ Confusion**
- `jenkins-agent` was already added to docker group
- Docker service was running
- Error still persisted in Jenkins pipeline

**🔍 Root Cause (Critical Linux Behavior)**

Linux does NOT apply new group memberships to already-running processes.

Jenkins agent process was started **before**:
```bash
usermod -aG docker jenkins-agent
```

Therefore, Jenkins agent continued running with **old group permissions**.

This is a **process lifecycle issue**, not a Docker or Jenkins issue.

**✅ Correct Fix (Production-Safe)**

Restart the Jenkins agent process so it reloads group permissions:
```bash
systemctl restart jenkins-agent
```

**🔎 Verification (Mandatory Before Re-running Pipeline)**
```bash
sudo -u jenkins-agent docker ps
```

- If this command works → pipeline will also work
- If it fails → agent restart was missed

**💡 Lesson Learned**

> Adding a user to a group is useless until the process using that user is restarted.

---

### Problem 9: .env File Confusion During Docker Build

**🔴 Error**
```
Can't add file .env to tar: io: read/write on closed pipe
```

**❓ Initial Confusion**
- `.env` file seemed missing
- Permission commands failed: `No such file or directory`

**🔍 Root Cause**

Jenkins executes builds inside its workspace, not inside the user's home directory.

**Actual workspace**:
```
/var/lib/jenkins-agent/workspace/devsecops/client
```

**But debugging was initially done in**:
```
/home/azureuser
```

So `.env` was checked in the wrong directory.

**✅ Correct Fix**

Navigate to Jenkins workspace:
```bash
cd /var/lib/jenkins-agent/workspace/devsecops/client
```

Fix ownership and permissions:
```bash
chown jenkins-agent:jenkins-agent .env
chmod 644 .env
```

**🔐 Security Best Practice Applied**

Even after fixing permissions:
- `.env` added to `.dockerignore`
- `.env` excluded from Docker build context
- Secrets injected only at runtime
- Prevented accidental secret leakage into Docker images

**💡 Lesson Learned**

> Jenkins workspace is the only directory that matters for builds — user home directories are irrelevant.

---

### Problem 10: CI Build Failing Due to ESLint Warnings

**🔴 Error**
```
Treating warnings as errors because process.env.CI = true
```

**❓ Confusion**
- Code built successfully on local machine
- Failed only in Jenkins

**🔍 Root Cause**

CI environments automatically set:
```bash
CI=true
```

In React (Create React App):
- Any ESLint warning = build failure in CI

Issues included:
- `useEffect` missing dependencies
- Unused imports

**✅ Correct Fix (Not a Workaround)**

- Refactored code using `useCallback`
- Fixed hook dependency arrays properly
- Removed unused imports

> CI strictness revealed real production risks, not false errors.

**💡 Lesson Learned**

> CI does not break builds — it exposes unsafe code patterns early.

---

### Problem 11: Misunderstanding Jenkins Tool Configuration

**❓ Confusion**

> "Tools are declared in Jenkinsfile, so Jenkins installs them automatically?"

**🔍 Root Cause**

Jenkinsfile:
```groovy
tools {
  nodejs 'nodejs-18'
}
```

This does **NOT** install tools.

It only **references** tools that are:
- Pre-configured in: **Manage Jenkins → Global Tool Configuration**

**✅ Correct Understanding**

| Layer | Responsibility |
|-------|----------------|
| **Jenkinsfile** | References tools |
| **Jenkins Admin** | Installs & configures tools |
| **Agent OS** | Executes binaries |

**💡 Lesson Learned**

> Jenkinsfile is declarative — infrastructure must already exist.

---

## 🧠 Meta-Learning From These Issues

All issues fell into one of three categories:

1. **OS-level permissions** (Docker socket, npm, file ownership)
2. **Process lifecycle misunderstandings** (group changes not applied)
3. **CI vs local environment differences** (CI=true, strict linting)

> **None of these were tool bugs.**  
> They were system design & execution model misunderstandings — exactly what DevSecOps aims to solve.

---

## 🧩 Why This Matters (Interview-Ready Insight)

> **Most CI/CD failures are not caused by Jenkins, Docker, or tools**  
> **but by misunderstanding where code runs, under which user, and with what permissions.**

This pipeline was not just built — it was **debugged the way production systems are debugged**.

---

## 🔒 Security Practices Followed

- ✅ No secrets in pipeline code
- ✅ No credentials in repository
- ✅ Agent-level isolation
- ✅ No root execution
- ✅ Quality gates enforced
- ✅ Binary-based tooling

---

## 🎯 Final Outcome

- ✅ Fully functional DevSecOps pipeline
- ✅ Secure CI/CD lifecycle
- ✅ Real-world production issues solved
- ✅ Agent-based scalable architecture

---

## 💡 Key Takeaway

> **DevSecOps is not about adding tools.**
> 
> **It is about understanding failures, root causes, and fixing them correctly without breaking security boundaries.**

---

## 📚 Next Steps

Want to dive deeper? Consider:

- 🔹 Short interview-ready version
- 🔹 Architecture diagram
- 🔹 Resume bullet points
- 🔹 Annotated Jenkinsfile walkthrough
