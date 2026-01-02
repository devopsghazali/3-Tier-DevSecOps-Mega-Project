pipeline {
  agent { label 'agent-1' }

  environment {
    SONARQUBE_ENV = 'sonarqube'

    CLIENT_PROJECT = 'node-client'
    API_PROJECT    = 'node-api'

    CLIENT_IMAGE = 'addyour docker hub username/node-client'
    API_IMAGE    = 'add your docker hub usename/node-api'
  }

  options {
    timestamps()
    disableConcurrentBuilds()
  }

  stages {

    stage('Checkout Code') {
      steps {
        git branch: 'main',
            url: 'https://github.com/devopsghazali/3-Tier-DevSecOps-Mega-Project.git'
      }
    }
    stage('Docker Access Test') {
  steps {
    sh '''
      whoami
      id
      groups
      docker ps
    '''
  }
}

    /* ================= CLIENT ================= */

    stage('Gitleaks - Client') {
      steps {
        dir('client') {
          sh '''
            gitleaks detect --source . --no-git --redact --exit-code 1
          '''
        }
      }
    }

    stage('SonarQube - Client') {
      steps {
        dir('client') {
          withSonarQubeEnv("${SONARQUBE_ENV}") {
            sh '''
              sonar-scanner \
                -Dsonar.projectKey=${CLIENT_PROJECT} \
                -Dsonar.projectName=${CLIENT_PROJECT} \
                -Dsonar.sources=.
            '''
          }
        }
      }
    }

    stage('Build Client') {
      steps {
        dir('client') {
          sh '''
            npm install
            npm run build
          '''
        }
      }
    }

    stage('Docker Build - Client') {
      steps {
        dir('client') {
          sh 'docker build -t ${CLIENT_IMAGE}:latest .'
        }
      }
    }

    stage('Trivy Scan - Client Image') {
      steps {
        sh '''
          trivy image --severity HIGH,CRITICAL --exit-code 1 ${CLIENT_IMAGE}:latest
        '''
      }
    }

    /* ================= API ================= */

    stage('Gitleaks - API') {
      steps {
        dir('api') {
          sh '''
            gitleaks detect --source . --no-git --redact --exit-code 1
          '''
        }
      }
    }

    stage('SonarQube - API') {
      steps {
        dir('api') {
          withSonarQubeEnv("${SONARQUBE_ENV}") {
            sh '''
              sonar-scanner \
                -Dsonar.projectKey=${API_PROJECT} \
                -Dsonar.projectName=${API_PROJECT} \
                -Dsonar.sources=.
            '''
          }
        }
      }
    }

    stage('Build API') {
      steps {
        dir('api') {
          sh '''
            npm install
          '''
        }
      }
    }

    stage('Docker Build - API') {
      steps {
        dir('api') {
          sh 'docker build -t ${API_IMAGE}:latest .'
        }
      }
    }

    stage('Trivy Scan - API Image') {
      steps {
        sh '''
          trivy image --severity HIGH,CRITICAL --exit-code 1 ${API_IMAGE}:latest
        '''
      }
    }

    /* ================= PUSH ================= */

    stage('Push Images to Docker Hub') {
      steps {
        withCredentials([usernamePassword(
          credentialsId: 'hn',
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASS'
        )]) {
          sh '''
            echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin

            docker push ${CLIENT_IMAGE}:latest
            docker push ${API_IMAGE}:latest

            docker logout
          '''
        }
      }
    }
  }

  post {
    success {
      echo "✅ CLIENT + API — BOTH IMAGES BUILT, SCANNED & PUSHED"
    }
    failure {
      echo "❌ PIPELINE FAILED — SECURITY / BUILD ISSUE"
    }
  }
}
