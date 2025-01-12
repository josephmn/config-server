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
        CONTAINER_PORT = '8888'
        HOST_PORT = '8888'
        // NVD_API_KEY = credentials('NVD_API_KEY') // Carga la API Key desde las credenciales de Jenkins
        NETWORK = 'azure-net'
    }

    parameters {
        booleanParam(name: 'SONARQUBE', defaultValue: false, description: 'Ejecutar analisis de SonarQube?')
        booleanParam(name: 'OWASP', defaultValue: false, description: 'Ejecutar analisis de OWASP?')
        booleanParam(name: 'DOCKER', defaultValue: true, description: 'Desplegar y Ejecutar APP en DOCKER?')
        booleanParam(name: 'NOTIFICATION', defaultValue: true, description: 'Deseas notificar por correo?')
        string(name: 'CORREO', defaultValue: 'josephcarlos.jcmn@gmail.com', description: 'Deseas notificar por correo a los siguientes correos?')
    }

    stages {
        stage('Compile') {
            steps {
                script {
                    if (params.NOTIFICATION) {
                        notifyJob('JOB Jenkins', params.CORREO)
                    }
                    echo "######################## : ======> EJECUTANDO COMPILE..."
                    // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                    bat 'mvn clean compile'
                }
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
                expression { params.DOCKER }
            }
            steps {
                script {
                    echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."

                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()
                    // Remover -SNAPSHOT si existe, solo para PRD, en desarrollo no se quita
                    // version = version.replaceAll("-SNAPSHOT", "")

                    echo "######################## : ======> VERSIÓN A DESPLEGAR: ${version}"
                    echo "######################## : ======> APLICATIVO + VERSION: ${NAME_APP}:${version}"
                    // Usar la versión capturada para los comandos Docker
                    bat """
                        echo "Limpiando contenedores e imágenes anteriores..."
                        docker rm -f ${NAME_APP} || true
                        docker rmi -f ${NAME_APP}:${version} || true

                        echo "Construyendo nueva imagen con versión ${version}..."
                        docker build --build-arg NAME_APP=${NAME_APP} --build-arg JAR_VERSION=${version} -t ${NAME_APP}:${version} .

                        echo "Desplegando contenedor..."
                        docker run -d --name ${NAME_APP} -p ${HOST_PORT}:${CONTAINER_PORT} --network=${NETWORK} ${NAME_APP}:${version}
                    """
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('SUCCESS', params.CORREO)
                }
            }
        }
        failure {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('FAIL', params.CORREO)
                }
            }
        }
    }
}
