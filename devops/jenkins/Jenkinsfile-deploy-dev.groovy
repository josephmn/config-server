@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server'
        SCANNER_HOME = tool 'sonar-scanner'
        // NVD_API_KEY = credentials('NVD_API_KEY') // Carga la API Key desde las credenciales de Jenkins
        NETWORK = 'azure-net'
    }

    parameters {
        booleanParam(name: 'SONARQUBE', defaultValue: true, description: '¿Ejecutar análisis de SonarQube?')
        booleanParam(name: 'OWASP', defaultValue: true, description: '¿Ejecutar análisis de OWASP?')
        booleanParam(name: 'DOCKER', defaultValue: true, description: '¿Desplegar y Ejecutar APP en DOCKER?')
    }

    stages {
        stage('Compile') {
            steps {
                echo "######################## : ======> EJECUTANDO COMPILE..."
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat 'mvn clean compile'
            }
        }

        stage('QA SonarQube') {
            when {
                expression { params.SONARQUBE } // Ejecutar sólo si el parámetro es verdadero
            }
            steps {
                withSonarQubeEnv('sonar-server') {
                    withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                        echo "######################## : ======> EJECUTANDO QA SONARQUBE..."
                        bat """
                            "${SCANNER_HOME}\\bin\\sonar-scanner" ^
                            -Dsonar.url=http://localhost:9000/ ^
                            -Dsonar.login=${SONAR_TOKEN} ^
                            -Dsonar.projectName=config-server ^
                            -Dsonar.java.binaries=. ^
                            -Dsonar.projectKey=config-server
                        """
                    }
                }
            }
        }

        stage('OWASP Scan') {
            when {
                expression { params.OWASP } // Ejecutar sólo si el parámetro es verdadero
            }
            steps {
                echo "######################## : ======> EJECUTANDO OWASP SCAN..."
                // dependencyCheck additionalArguments: '--scan ./ --format HTML', odcInstallation: 'DP'
                // dependencyCheck additionalArguments: "--scan ./ --nvdApiKey=${NVD_API_KEY}", odcInstallation: 'DP' // con API KEY
                dependencyCheck additionalArguments: '--scan ./ ', odcInstallation: 'DP' // usaba este
                // dependencyCheck additionalArguments: '--scan ./ --disableCentral --disableRetired --disableExperimental', odcInstallation: 'DP'
                dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
            }
        }

        stage('Build Application with Maven') {
            steps {
                echo "######################## : ======> EJECUTANDO BUILD APPLICATION MAVEN..."
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat 'mvn clean install'
            }
        }

        stage('Creating Network for Docker') {
            steps {
                script {
                    echo "######################## : ======> EJECUTANDO CREACIÓN DE RED PARA DOCKER..."
                    def networkExists = bat(
                            script: "docker network ls | findstr ${NETWORK}",
                            returnStatus: true
                    )
                    if (networkExists != 0) {
                        echo "######################## : ======> La red '${NETWORK}' no existe. Creándola..."
                        bat "docker network create --attachable ${NETWORK}"
                    } else {
                        echo "######################## : ======> La red '${NETWORK}' ya existe. No es necesario crearla."
                    }
                }
            }
        }

        stage('Docker Build and Run') {
            when {
                expression { params.DOCKER } // Ejecutar sólo si el parámetro es verdadero
            }
            steps {
                script {
                    echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."
                    // Nombre de la imagen Docker
                    // def containerName = "config-server"
                    // def imageName = "config-server"
                    // Eliminar el contenedor existente si ya está en ejecución
                    bat "docker rm -f ${NAME_APP} || true"
                    // Eliminar la imagen existente si ya existe
                    bat "docker rmi -f ${NAME_APP} || true"
                    // Construir la imagen Docker usando el Dockerfile en la raíz del proyecto
                    bat "docker build -t ${NAME_APP}:1.0 ."
                    // Ejecutar el contenedor de la imagen recién creada
                    bat "docker run -d --name ${NAME_APP} -p 8888:8888 --network=${NETWORK} ${NAME_APP}:1.0"
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.SEND_SUCCESS_NOTIFICATION) {
                    notifyByMail('SUCCESS', 'josephcarlos.jcmn@gmail.com')
                }
            }
        }
        failure {
            script {
                notifyByMail('FAIL', 'josephcarlos.jcmn@gmail.com')
            }
        }
    }
}
