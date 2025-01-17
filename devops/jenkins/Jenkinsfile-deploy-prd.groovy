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
        NETWORK = 'azure-net'
        GITHUB_TOKEN = credentials('GitHub_Access_SSH') // Añadido para GitHub Releases
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
        GIT_COMMITTER_NAME = 'Joseph Magallanes'
        // Añadimos configuración de Git para el release
        RELEASE_BRANCH = 'develop'
        GIT_CREDENTIALS_ID = 'GitHub_Access'
    }

    parameters {
        booleanParam(name: 'CREATE_RELEASE', defaultValue: true, description: 'Crear un nuevo release?')
        booleanParam(name: 'DOCKER', defaultValue: true, description: 'Desplegar y Ejecutar APP en DOCKER?')
        booleanParam(name: 'NOTIFICATION', defaultValue: false, description: 'Deseas notificar por correo?')
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
                    bat 'mvn clean compile'
                }
            }
        }

        stage('Prepare Release') {
            when {
                expression { params.CREATE_RELEASE }
            }
            steps {
                script {
//                    withCredentials([usernamePassword(credentialsId: 'GitHub_Access_SSH', variable: 'GITHUB_TOKEN')]) {
                        echo "######################## : ======> PREPARANDO RELEASE..."

                        echo "######################## : ======> AGREGANDO DIRECTORIO SEGURO... ${WORKSPACE}"
                        // Configuración de Git
                        bat """
                            git config --global --add safe.directory "${WORKSPACE}"
                        """

                        echo "######################## : ======> LISTAR CONFIGURACION..."
                        bat "git config --global --list"

                        // Asegurar que estamos en la rama correcta
                        bat "git checkout ${RELEASE_BRANCH}"
                        bat "git pull origin ${RELEASE_BRANCH}"

                        bat "git remote set-url origin git@github.com:josephmn/config-server.git"

                        // Preparar el release
                        bat 'mvn release:prepare -B'
//                    }
                }
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

                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()
                    // Remover -SNAPSHOT si existe, solo para PRD, en desarrollo no se quita
                     version = version.replaceAll("-SNAPSHOT", "")

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