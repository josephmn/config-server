@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server'
        CONTAINER_PORT = '8888'
        HOST_PORT = '8888'
        NETWORK = 'azure-net'
        GIT_CREDENTIALS = credentials('PATH_Jenkins')
    }

    parameters {
        booleanParam(name: 'CREATE_RELEASE', defaultValue: true, description: 'Crear un nuevo release?')
        booleanParam(name: 'DOCKER', defaultValue: true, description: 'Desplegar y Ejecutar APP en DOCKER?')
        booleanParam(name: 'NOTIFICATION', defaultValue: false, description: 'Deseas notificar por correo?')
        string(name: 'CORREO', defaultValue: 'josephcarlos.jcmn@gmail.com', description: 'Deseas notificar por correo a los siguientes correos?')
        string(name: 'NEW_VERSION', defaultValue: '1.0.1', description: 'Nueva version para el release (ej. 1.0.1)')
    }

    stages {
        stage('Compile') {
            steps {
                script {
                    if (params.NOTIFICATION) {
                        notifyJob('JOB Jenkins', params.CORREO)
                    }
                    echo "######################## : ======> EJECUTANDO COMPILE..."
                    bat 'mvn clean compile'
                }
            }
        }

        stage('Build Application with Maven') {
            steps {
                echo "######################## : ======> EJECUTANDO BUILD APPLICATION MAVEN..."
                bat 'mvn clean install'
            }
        }

        stage('Prepare Release') {
            steps {
                script {
                    echo "######################## : ======> PREPARANDO RELEASE..."

                    // Configurar usuario de Git en Jenkins
                    bat 'git config --global user.name "Joseph Magallanes"'
                    bat 'git config --global user.email "josephcarlos.jcmn@gmail.com"'

                    // Configurar credenciales de Git
                    bat "git remote set-url origin https://${env.GIT_CREDENTIALS_USR}:${env.GIT_CREDENTIALS_PSW}@github.com/josephmn/config-server.git"

                    // Cambiar la versión en el pom.xml y preparar el release
                    bat "mvn release:prepare -Dtag=${params.NEW_VERSION} -DautoVersionSubmodules=true"

                    // Realizar el release
                    bat "mvn release:perform"
                }
            }
//            steps {
//                script {
//                    echo "######################## : ======> PREPARANDO RELEASE..."
//
//                    // Configurar usuario de Git en Jenkins
//                    bat 'git config --global user.name "Joseph Magallanes"'
//                    bat 'git config --global user.email "josephcarlos.jcmn@gmail.com"'
//
//                    // Configurar credenciales de Git
//                    bat "git remote set-url origin https://${env.GIT_CREDENTIALS_USR}:${env.GIT_CREDENTIALS_PSW}@github.com/josephmn/config-server.git"
//
//                    // Cambiar la versión en el pom.xml
//                    bat "mvn versions:set -DnewVersion=${params.NEW_VERSION}"
//                    bat "mvn versions:commit"
//
//                    // Hacer commit y push de la nueva versión
//                    bat "git add pom.xml"
//                    bat "git commit -m \"Incrementar version a ${params.NEW_VERSION}\"" // Cambia a comillas dobles
//
//                    // Hacer push de la nueva versión
//                    bat "git push origin develop" // Cambia 'develop' por tu rama principal
//
//                    // Preparar el release
//                    bat "mvn release:prepare"
//                    bat "mvn release:perform"
//                }
//            }
        }

        stage('Creating Network for Docker') {
            steps {
                script {
                    echo "######################## : ======> EJECUTANDO CREACION DE RED PARA DOCKER..."
                    def networkExists = bat(
                            script: "docker network ls | findstr ${NETWORK}",
                            returnStatus: true
                    )
                    if (networkExists != 0) {
                        echo "######################## : ======> La red '${NETWORK}' no existe. Creandola..."
                        bat "docker network create -- attachable ${NETWORK}"
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