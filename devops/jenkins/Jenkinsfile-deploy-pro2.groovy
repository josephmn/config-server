@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server'
        NAME_IMG_DOCKER = 'config-server-cer'
        CONTAINER_PORT = '8888'
        HOST_PORT = '8888'
        NETWORK = 'azure-net'
        GIT_CREDENTIALS = credentials('PATH_Jenkins')
        GIT_COMMITTER_NAME = 'josephmn'
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
    }

    parameters {
        booleanParam(name: 'DOCKER', defaultValue: true, description: 'Desplegar y Ejecutar APP en DOCKER?')
        booleanParam(name: 'NOTIFICATION', defaultValue: false, description: 'Deseas notificar por correo?')
        string(name: 'CORREO', defaultValue: 'josephcarlos.jcmn@gmail.com', description: 'Correo para notificaciones')
    }

    stages {
        stage('Checkout Code') {
            steps {
                echo "######################## : ======> Clonando código de PRD..."
                script {
                    bat "git clone -b main https://${env.GIT_CREDENTIALS_USR}:${env.GIT_CREDENTIALS_PSW}@github.com/josephmn/config-server.git ."
                }
            }
        }

        stage('Merge to Production') {
            when {
                expression { params.MERGE }
            }
            steps {
                echo "######################## : ======> Haciendo merge de la version estable a main..."
                script {
                    bat """
                        git checkout main
                        git merge --no-ff release/${version}
                        git push origin main
                    """
                }
            }
        }

        stage('Compile and Build') {
            steps {
                echo "######################## : ======> Compilando y construyendo la aplicación..."
                bat 'mvn clean install'
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
