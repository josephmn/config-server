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
        NETWORK = 'azure-net'
        GITHUB_TOKEN = credentials('github-token') // Añadido para GitHub Releases
    }

    parameters {
        booleanParam(name: 'DOCKER', defaultValue: true, description: '¿Desplegar y Ejecutar APP en DOCKER?')
        booleanParam(name: 'NOTIFICATION', defaultValue: true, description: '¿Deseas notificar por correo?')
        booleanParam(name: 'CREATE_RELEASE', defaultValue: true, description: '¿Crear un nuevo release?')
        string(name: 'CORREO', defaultValue: 'josephcarlos.jcmn@gmail.com', description: '¿Deseas notificar por correo a los siguientes correos?')
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

        stage('Prepare Release') {
            when {
                expression { params.CREATE_RELEASE }
            }
            steps {
                echo "######################## : ======> PREPARANDO RELEASE..."
                bat 'mvn release:prepare -B'
            }
        }

        stage('Perform Release') {
            when {
                expression { params.CREATE_RELEASE }
            }
            steps {
                echo "######################## : ======> EJECUTANDO RELEASE..."
                bat 'mvn release:perform -B'
            }
        }

        stage('Create GitHub Release') {
            when {
                expression { params.CREATE_RELEASE }
            }
            steps {
                script {
                    echo "######################## : ======> CREANDO GITHUB RELEASE..."
                    def version = bat(script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout', returnStdout: true).trim()
                    version = version.replaceAll("-SNAPSHOT", "")

                    bat """
                        curl -X POST ^
                        -H "Authorization: token %GITHUB_TOKEN%" ^
                        -H "Accept: application/vnd.github.v3+json" ^
                        https://api.github.com/repos/josephmn/config-server/releases ^
                        -d "{^
                            \\"tag_name\\": \\"v${version}\\",^
                            \\"name\\": \\"Release ${version}\\",^
                            \\"body\\": \\"Release de la versión ${version}\\",^
                            \\"draft\\": false,^
                            \\"prerelease\\": false^
                        }"
                    """
                }
            }
        }

        stage('Build Application with Maven') {
            steps {
                echo "######################## : ======> EJECUTANDO BUILD APPLICATION MAVEN..."
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
                    // Obtener la versión actual del proyecto
                    def version = bat(script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout', returnStdout: true).trim()
                    // Remover -SNAPSHOT si existe
                    version = version.replaceAll("-SNAPSHOT", "")

                    echo "######################## : ======> VERSIÓN A DESPLEGAR: ${version}"

                    bat "docker rm -f ${NAME_APP} || true"
                    bat "docker rmi -f ${NAME_APP}:${version} || true"
                    // Usar la versión en la construcción de la imagen
                    bat "docker build -t ${NAME_APP}:${version} ."
                    // Usar la versión al ejecutar el contenedor
                    bat "docker run -d --name ${NAME_APP} -p 8888:8888 --network=${NETWORK} ${NAME_APP}:${version}"

                    // Opcional: también crear un tag 'latest'
                    bat "docker tag ${NAME_APP}:${version} ${NAME_APP}:latest"
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